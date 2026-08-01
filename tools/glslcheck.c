// Headless GLSL compile+link checker for the Impetus/Iris port.
// No EGL/GL headers on this box, so every entry point is declared by hand and resolved through dlopen.
// Usage: glslcheck <dir> [<program> ...]   (defaults to every <program>.vsh/<program>.fsh pair plus every
//                                            <program>.csh compute program in <dir>)
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <dirent.h>

typedef void *EGLDisplay, *EGLConfig, *EGLContext, *EGLSurface;
typedef unsigned int EGLenum, EGLBoolean, GLenum, GLuint;
typedef int EGLint, GLint, GLsizei;

#define EGL_NONE 0x3038
#define EGL_SURFACE_TYPE 0x3033
#define EGL_PBUFFER_BIT 0x0001
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_OPENGL_BIT 0x0008
#define EGL_WIDTH 0x3057
#define EGL_HEIGHT 0x3056
#define EGL_OPENGL_API 0x30A2
#define EGL_CONTEXT_MAJOR_VERSION 0x3098
#define EGL_CONTEXT_MINOR_VERSION 0x30FB
#define EGL_CONTEXT_OPENGL_PROFILE_MASK 0x30FD
#define EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT 0x00000002

#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_COMPUTE_SHADER 0x91B9
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82
#define GL_RENDERER 0x1F01
#define GL_VERSION 0x1F02

static EGLDisplay (*eglGetDisplay)(void *);
static EGLBoolean (*eglInitialize)(EGLDisplay, EGLint *, EGLint *);
static EGLBoolean (*eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
static EGLBoolean (*eglBindAPI)(EGLenum);
static EGLContext (*eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
static EGLSurface (*eglCreatePbufferSurface)(EGLDisplay, EGLConfig, const EGLint *);
static EGLBoolean (*eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
static void *(*eglGetProcAddress)(const char *);

static GLuint (*glCreateShader)(GLenum);
static void (*glShaderSource)(GLuint, GLsizei, const char *const *, const GLint *);
static void (*glCompileShader)(GLuint);
static void (*glGetShaderiv)(GLuint, GLenum, GLint *);
static void (*glGetShaderInfoLog)(GLuint, GLsizei, GLsizei *, char *);
static GLuint (*glCreateProgram)(void);
static void (*glAttachShader)(GLuint, GLuint);
static void (*glBindFragDataLocation)(GLuint, GLuint, const char *);
static void (*glLinkProgram)(GLuint);
static void (*glGetProgramiv)(GLuint, GLenum, GLint *);
static void (*glGetProgramInfoLog)(GLuint, GLsizei, GLsizei *, char *);
static void (*glDeleteShader)(GLuint);
static void (*glDeleteProgram)(GLuint);
static const unsigned char *(*glGetString)(GLenum);
static GLint (*glGetUniformLocation)(GLuint, const char *);

static char *slurp(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = malloc(n + 1);
    if (fread(buf, 1, n, f) != (size_t)n) { fclose(f); free(buf); return NULL; }
    buf[n] = 0;
    fclose(f);
    return buf;
}

static int compile_stage(GLenum type, const char *src, const char *label, GLuint *out) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, NULL);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[16384];
        GLsizei len = 0;
        glGetShaderInfoLog(sh, sizeof(log) - 1, &len, log);
        log[len] = 0;
        printf("  COMPILE FAIL %s\n%s\n", label, log);
        glDeleteShader(sh);
        return 0;
    }
    *out = sh;
    return 1;
}

static int check_program(const char *dir, const char *name) {
    char vpath[4096], fpath[4096];
    snprintf(vpath, sizeof(vpath), "%s/%s.vsh", dir, name);
    snprintf(fpath, sizeof(fpath), "%s/%s.fsh", dir, name);
    char *vsrc = slurp(vpath), *fsrc = slurp(fpath);
    if (!vsrc || !fsrc) { free(vsrc); free(fsrc); return 1; }

    GLuint vs = 0, fs = 0;
    int ok = compile_stage(GL_VERTEX_SHADER, vsrc, name, &vs);
    ok &= compile_stage(GL_FRAGMENT_SHADER, fsrc, name, &fs);
    free(vsrc); free(fsrc);
    if (!ok) { if (vs) glDeleteShader(vs); if (fs) glDeleteShader(fs); return 0; }

    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    // Same binding the pipeline uses for the generated 330-core output array.
    glBindFragDataLocation(prog, 0, "iris_FragData");
    glLinkProgram(prog);
    GLint linked = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[16384];
        GLsizei len = 0;
        glGetProgramInfoLog(prog, sizeof(log) - 1, &len, log);
        log[len] = 0;
        printf("  LINK FAIL %s\n%s\n", name, log);
    } else {
        // Which #if branch the driver actually kept, read off the live program: a uniform the compiler
        // eliminated reports -1. In Sildur's gbuffers_water, colortex4 is referenced ONLY inside the two
        // `defined(IS_IRIS) || MC_VERSION >= 11604` blocks (funReflections + funFog), so
        // colortex4 != -1 <=> the Iris branch compiled. In composite1, colortex2 is declared only inside
        // the `!defined(IS_IRIS) && MC_VERSION < 11604` block, so colortex2 != -1 <=> the legacy branch.
        const char *probes[] = { "colortex2", "colortex4", "colortex6" };
        printf("  ok %-22s", name);
        for (int i = 0; i < 3; i++) {
            printf("  %s=%d", probes[i], glGetUniformLocation(prog, probes[i]));
        }
        printf("\n");
    }
    glDeleteShader(vs); glDeleteShader(fs); glDeleteProgram(prog);
    return linked ? 1 : 0;
}

/* Compute programs (`<name>.csh`) link on their own — Iris's letter-suffixed compute variants
   (deferred4_a.csh) have no vertex/fragment pair at all. */
static int check_compute(const char *dir, const char *name) {
    char cpath[4096];
    snprintf(cpath, sizeof(cpath), "%s/%s.csh", dir, name);
    char *csrc = slurp(cpath);
    if (!csrc) return 1;

    GLuint cs = 0;
    int ok = compile_stage(GL_COMPUTE_SHADER, csrc, name, &cs);
    free(csrc);
    if (!ok) { if (cs) glDeleteShader(cs); return 0; }

    GLuint prog = glCreateProgram();
    glAttachShader(prog, cs);
    glLinkProgram(prog);
    GLint linked = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[16384];
        GLsizei len = 0;
        glGetProgramInfoLog(prog, sizeof(log) - 1, &len, log);
        log[len] = 0;
        printf("  LINK FAIL %s (compute)\n%s\n", name, log);
    } else {
        printf("  ok %-22s  (compute)  colorimg4=%d  colortex4=%d\n", name,
               glGetUniformLocation(prog, "colorimg4"), glGetUniformLocation(prog, "colortex4"));
    }
    glDeleteShader(cs); glDeleteProgram(prog);
    return linked ? 1 : 0;
}

#define GET(lib, sym) do { *(void **)(&sym) = dlsym(lib, #sym); \
    if (!sym) { fprintf(stderr, "missing %s\n", #sym); return 2; } } while (0)
#define GETGL(sym) do { *(void **)(&sym) = eglGetProcAddress(#sym); \
    if (!sym) { fprintf(stderr, "missing GL %s\n", #sym); return 2; } } while (0)

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s <dir> [program ...]\n", argv[0]); return 2; }
    void *egl = dlopen("libEGL.so.1", RTLD_LAZY);
    if (!egl) { fprintf(stderr, "dlopen libEGL: %s\n", dlerror()); return 2; }
    GET(egl, eglGetDisplay); GET(egl, eglInitialize); GET(egl, eglChooseConfig);
    GET(egl, eglBindAPI); GET(egl, eglCreateContext); GET(egl, eglCreatePbufferSurface);
    GET(egl, eglMakeCurrent); GET(egl, eglGetProcAddress);

    // NVIDIA's EGL has no default display in a headless sandbox; enumerate devices and use the device platform.
    EGLDisplay dpy = NULL;
    EGLBoolean (*eglQueryDevicesEXT)(EGLint, void **, EGLint *) = eglGetProcAddress("eglQueryDevicesEXT");
    EGLDisplay (*eglGetPlatformDisplayEXT)(EGLenum, void *, const EGLint *) = eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (eglQueryDevicesEXT && eglGetPlatformDisplayEXT) {
        void *devs[16]; EGLint ndev = 0;
        if (eglQueryDevicesEXT(16, devs, &ndev)) {
            for (EGLint i = 0; i < ndev && !dpy; i++) {
                EGLDisplay d = eglGetPlatformDisplayEXT(0x313F /* EGL_PLATFORM_DEVICE_EXT */, devs[i], NULL);
                if (d && eglInitialize(d, NULL, NULL)) dpy = d;
            }
        }
    }
    if (!dpy) {
        dpy = eglGetDisplay((void *)0);
        if (!dpy || !eglInitialize(dpy, NULL, NULL)) { fprintf(stderr, "eglInitialize failed\n"); return 2; }
    }
    if (!eglBindAPI(EGL_OPENGL_API)) { fprintf(stderr, "eglBindAPI(OpenGL) failed\n"); return 2; }
    EGLint cfgattr[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_NONE };
    EGLConfig cfg; EGLint ncfg = 0;
    if (!eglChooseConfig(dpy, cfgattr, &cfg, 1, &ncfg) || ncfg < 1) { fprintf(stderr, "no EGL config\n"); return 2; }
    // Compatibility profile: the generated sources are 330 core but the terrain/gbuffer path can still
    // reference legacy builtins on some packs; compat accepts a superset, so it never masks a real error.
    EGLint ctxattr[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                         EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, (EGLContext)0, ctxattr);
    if (!ctx) { fprintf(stderr, "eglCreateContext failed\n"); return 2; }
    EGLint surfattr[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, surfattr);
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) { fprintf(stderr, "eglMakeCurrent failed\n"); return 2; }

    GETGL(glCreateShader); GETGL(glShaderSource); GETGL(glCompileShader); GETGL(glGetShaderiv);
    GETGL(glGetShaderInfoLog); GETGL(glCreateProgram); GETGL(glAttachShader);
    GETGL(glBindFragDataLocation); GETGL(glLinkProgram); GETGL(glGetProgramiv);
    GETGL(glGetProgramInfoLog); GETGL(glDeleteShader); GETGL(glDeleteProgram); GETGL(glGetString); GETGL(glGetUniformLocation);

    printf("GL %s on %s\n", (const char *)glGetString(GL_VERSION), (const char *)glGetString(GL_RENDERER));

    int failures = 0, total = 0;
    if (argc > 2) {
        for (int i = 2; i < argc; i++) { total++; failures += !check_program(argv[1], argv[i]); }
    } else {
        DIR *d = opendir(argv[1]);
        if (!d) { fprintf(stderr, "opendir %s\n", argv[1]); return 2; }
        struct dirent *e;
        char names[512][128]; int n = 0;
        char computes[512][128]; int nc = 0;
        while ((e = readdir(d)) && n < 512 && nc < 512) {
            char *dot = strrchr(e->d_name, '.');
            if (!dot) continue;
            size_t len = dot - e->d_name;
            if (len >= sizeof(names[0])) continue;
            if (strcmp(dot, ".fsh") == 0) {
                memcpy(names[n], e->d_name, len); names[n][len] = 0; n++;
            } else if (strcmp(dot, ".csh") == 0) {
                memcpy(computes[nc], e->d_name, len); computes[nc][len] = 0; nc++;
            }
        }
        closedir(d);
        for (int i = 0; i < n; i++) { total++; failures += !check_program(argv[1], names[i]); }
        for (int i = 0; i < nc; i++) { total++; failures += !check_compute(argv[1], computes[i]); }
    }
    printf("%d/%d programs linked\n", total - failures, total);
    return failures ? 1 : 0;
}

/*
 * Build:  gcc -O1 -o glslcheck tools/glslcheck.c -ldl
 * Run:    __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/10_nvidia.json \
 *             ./glslcheck <dir-of-dumped-stages> [program ...]
 *
 * Feed it either PackSmoke's output directory or the game's own `impetus_debug/src_*.{vsh,fsh}` dumps
 * (strip the `src_` prefix). Without the vendor override GLVND picks Mesa/llvmpipe, which rejects
 * `out vec4 iris_FragData[16]` ("insufficient contiguous locations") and mid-shader #extension lines that
 * the real NVIDIA driver accepts — those are false failures, so always pin the vendor.
 */
