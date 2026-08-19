// Headless repro for the "coloured dashes / grid on distant blocks" artifact.
//
// Builds a Minecraft-shaped block atlas (512x512, 16x16 sprites, per-sprite mip chains exactly like
// TextureUtil.uploadTextureMipmap builds them), draws a ground plane of 1x1 quads that each map one
// sprite's full UV rect, and reads the framebuffer back. Anything on that plane that is not the sand
// sprite's colour came from a *neighbouring* sprite, i.e. the sampler walked outside the sprite rect.
//
// Run it once per anisotropy level to see whether GL_TEXTURE_MAX_ANISOTROPY_EXT is what pushes the
// sample footprint across the sprite border.
//
// Build: gcc -O1 -o /tmp/anisocheck tools/anisocheck.c -ldl -lm
// Run:   __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/10_nvidia.json /tmp/anisocheck <outdir>
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <dlfcn.h>

typedef void *EGLDisplay, *EGLConfig, *EGLContext, *EGLSurface;
typedef unsigned int EGLenum, EGLBoolean, GLenum, GLuint, GLbitfield;
typedef int EGLint, GLint, GLsizei;
typedef float GLfloat;
typedef double GLdouble;
typedef unsigned char GLubyte;

#define EGL_NONE 0x3038
#define EGL_SURFACE_TYPE 0x3033
#define EGL_PBUFFER_BIT 0x0001
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_OPENGL_BIT 0x0008
#define EGL_WIDTH 0x3057
#define EGL_HEIGHT 0x3056
#define EGL_RED_SIZE 0x3024
#define EGL_GREEN_SIZE 0x3023
#define EGL_BLUE_SIZE 0x3022
#define EGL_OPENGL_API 0x30A2
#define EGL_CONTEXT_MAJOR_VERSION 0x3098
#define EGL_CONTEXT_MINOR_VERSION 0x30FB
#define EGL_CONTEXT_OPENGL_PROFILE_MASK 0x30FD
#define EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT 0x00000002

#define GL_TEXTURE_2D 0x0DE1
#define GL_RGBA 0x1908
#define GL_RGBA8 0x8058
#define GL_UNSIGNED_BYTE 0x1401
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_NEAREST 0x2600
#define GL_LINEAR 0x2601
#define GL_NEAREST_MIPMAP_LINEAR 0x2702
#define GL_LINEAR_MIPMAP_LINEAR 0x2703
#define GL_TEXTURE_BASE_LEVEL 0x813C
#define GL_TEXTURE_MAX_LEVEL 0x813D
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_REPEAT 0x2901
#define GL_TEXTURE_MAX_ANISOTROPY_EXT 0x84FE
#define GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT 0x84FF
#define GL_COLOR_BUFFER_BIT 0x00004000
#define GL_PROJECTION 0x1701
#define GL_MODELVIEW 0x1700
#define GL_QUADS 0x0007
#define GL_UNPACK_ALIGNMENT 0x0CF5
#define GL_PACK_ALIGNMENT 0x0D05
#define GL_VERSION 0x1F02
#define GL_RENDERER 0x1F01

static EGLDisplay (*eglGetDisplay)(void *);
static EGLBoolean (*eglInitialize)(EGLDisplay, EGLint *, EGLint *);
static EGLBoolean (*eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
static EGLBoolean (*eglBindAPI)(EGLenum);
static EGLContext (*eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
static EGLSurface (*eglCreatePbufferSurface)(EGLDisplay, EGLConfig, const EGLint *);
static EGLBoolean (*eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
static void *(*eglGetProcAddress)(const char *);

static void (*glGenTextures)(GLsizei, GLuint *);
static void (*glBindTexture)(GLenum, GLuint);
static void (*glTexImage2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *);
static void (*glTexParameteri)(GLenum, GLenum, GLint);
static void (*glTexParameterf)(GLenum, GLenum, GLfloat);
static void (*glGetFloatv)(GLenum, GLfloat *);
static void (*glEnable)(GLenum);
static void (*glClear)(GLbitfield);
static void (*glClearColor)(GLfloat, GLfloat, GLfloat, GLfloat);
static void (*glViewport)(GLint, GLint, GLsizei, GLsizei);
static void (*glMatrixMode)(GLenum);
static void (*glLoadIdentity)(void);
static void (*glFrustum)(GLdouble, GLdouble, GLdouble, GLdouble, GLdouble, GLdouble);
static void (*glRotatef)(GLfloat, GLfloat, GLfloat, GLfloat);
static void (*glTranslatef)(GLfloat, GLfloat, GLfloat);
static void (*glBegin)(GLenum);
static void (*glEnd)(void);
static void (*glTexCoord2f)(GLfloat, GLfloat);
static void (*glVertex3f)(GLfloat, GLfloat, GLfloat);
static void (*glColor3f)(GLfloat, GLfloat, GLfloat);
static void (*glReadPixels)(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void *);
static void (*glFinish)(void);
static void (*glPixelStorei)(GLenum, GLint);
static const GLubyte *(*glGetString)(GLenum);

#define ATLAS 512
#define SPRITE 16
#define GRID (ATLAS / SPRITE)
#define LEVELS 5 /* mipmapLevels = 4 -> levels 0..4, matching the vanilla default */

#define WIDTH 800
#define HEIGHT 450

/* The sprite the ground plane is textured with; every other slot in the atlas is a flat saturated
   colour so that any contamination is unmistakable and its source identifiable. */
#define SAND_COL 4
#define SAND_ROW 4

static unsigned char *level_data[LEVELS];

static unsigned rnd_state;
static unsigned rnd(void) { rnd_state = rnd_state * 1664525u + 1013904223u; return rnd_state >> 16; }

/* Colour of sprite (c,r) at base resolution, texel (x,y). */
static void sprite_texel(int c, int r, int x, int y, unsigned char *out) {
    if (c == SAND_COL && r == SAND_ROW) {
        /* Sand: a tan base with per-texel grain, like the vanilla sprite. Always r > g > b. */
        rnd_state = (unsigned)(x * 73856093 ^ y * 19349663);
        int jitter = (int)(rnd() % 25) - 12;
        out[0] = (unsigned char)(219 + jitter);
        out[1] = (unsigned char)(207 + jitter);
        out[2] = (unsigned char)(160 + jitter);
    } else {
        static const unsigned char palette[6][3] = {
            {0, 140, 120}, {150, 80, 210}, {200, 40, 40},
            {40, 60, 200}, {40, 180, 40}, {220, 40, 180},
        };
        const unsigned char *p = palette[(c * 31 + r) % 6];
        out[0] = p[0]; out[1] = p[1]; out[2] = p[2];
    }
    out[3] = 255;
}

/* Builds every mip level the way TextureUtil.uploadTextureMipmap does: each sprite is downsampled on
   its own and written at (originX >> level), so no level ever blends two sprites together. */
static void build_atlas(void) {
    for (int lvl = 0; lvl < LEVELS; lvl++) {
        int size = ATLAS >> lvl;
        level_data[lvl] = calloc((size_t)size * size, 4);
    }

    for (int r = 0; r < GRID; r++) {
        for (int c = 0; c < GRID; c++) {
            unsigned char base[SPRITE][SPRITE][4];
            for (int y = 0; y < SPRITE; y++) {
                for (int x = 0; x < SPRITE; x++) {
                    sprite_texel(c, r, x, y, base[y][x]);
                }
            }
            for (int lvl = 0; lvl < LEVELS; lvl++) {
                int step = 1 << lvl;
                int n = SPRITE >> lvl;
                int size = ATLAS >> lvl;
                int ox = (c * SPRITE) >> lvl, oy = (r * SPRITE) >> lvl;
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        int acc[4] = {0, 0, 0, 0};
                        for (int sy = 0; sy < step; sy++) {
                            for (int sx = 0; sx < step; sx++) {
                                const unsigned char *p = base[y * step + sy][x * step + sx];
                                for (int i = 0; i < 4; i++) acc[i] += p[i];
                            }
                        }
                        unsigned char *dst = &level_data[lvl][(((size_t)(oy + y) * size) + ox + x) * 4];
                        for (int i = 0; i < 4; i++) dst[i] = (unsigned char)(acc[i] / (step * step));
                    }
                }
            }
        }
    }
}

static GLuint upload_atlas(void) {
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    for (int lvl = 0; lvl < LEVELS; lvl++) {
        int size = ATLAS >> lvl;
        glTexImage2D(GL_TEXTURE_2D, lvl, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, level_data[lvl]);
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, LEVELS - 1);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    return tex;
}

static void draw_ground(void) {
    const float u0 = (float)(SAND_COL * SPRITE) / ATLAS;
    const float v0 = (float)(SAND_ROW * SPRITE) / ATLAS;
    const float u1 = u0 + (float)SPRITE / ATLAS;
    const float v1 = v0 + (float)SPRITE / ATLAS;

    glColor3f(1.0f, 1.0f, 1.0f);
    glBegin(GL_QUADS);
    for (int z = -240; z < 0; z++) {
        for (int x = -120; x < 120; x++) {
            /* One quad per block, each mapping the whole sprite - exactly how a block face is meshed. */
            glTexCoord2f(u0, v1); glVertex3f((float)x, 0.0f, (float)z + 1.0f);
            glTexCoord2f(u1, v1); glVertex3f((float)x + 1.0f, 0.0f, (float)z + 1.0f);
            glTexCoord2f(u1, v0); glVertex3f((float)x + 1.0f, 0.0f, (float)z);
            glTexCoord2f(u0, v0); glVertex3f((float)x, 0.0f, (float)z);
        }
    }
    glEnd();
}

/* Sand is the only tan sprite in the atlas: r > g > b with a wide r-b spread. Anything else on the
   plane leaked in from a neighbour. */
static int is_contaminated(const unsigned char *p) {
    return !(p[0] > p[1] && p[1] > p[2] && p[0] - p[2] > 30);
}

static void write_ppm(const char *path, const unsigned char *rgb) {
    FILE *f = fopen(path, "wb");
    if (!f) return;
    fprintf(f, "P6\n%d %d\n255\n", WIDTH, HEIGHT);
    /* glReadPixels is bottom-up. */
    for (int y = HEIGHT - 1; y >= 0; y--) fwrite(rgb + (size_t)y * WIDTH * 3, 1, (size_t)WIDTH * 3, f);
    fclose(f);
}

static void run(const char *outdir, const char *label, GLuint tex, int min_filter, float aniso) {
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, min_filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, aniso);

    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    double near_ = 0.05, top = near_ * tan(35.0 * M_PI / 180.0), right = top * ((double)WIDTH / HEIGHT);
    glFrustum(-right, right, -top, top, near_, 1000.0);

    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();
    glRotatef(12.0f, 1.0f, 0.0f, 0.0f); /* look slightly down, so the plane recedes to a horizon */
    glTranslatef(0.0f, -1.62f, 0.0f);

    glEnable(GL_TEXTURE_2D);
    draw_ground();
    glFinish();

    unsigned char *px = malloc((size_t)WIDTH * HEIGHT * 3);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, WIDTH, HEIGHT, 0x1907 /* GL_RGB */, GL_UNSIGNED_BYTE, px);

    long ground = 0, bad = 0;
    for (long i = 0; i < (long)WIDTH * HEIGHT; i++) {
        const unsigned char *p = px + i * 3;
        if (p[0] == 0 && p[1] == 0 && p[2] == 0) continue; /* sky */
        ground++;
        if (is_contaminated(p)) bad++;
    }
    printf("  %-28s contaminated %6ld / %7ld ground px  (%.3f%%)\n",
           label, bad, ground, ground ? 100.0 * bad / ground : 0.0);

    char path[4096];
    snprintf(path, sizeof(path), "%s/%s.ppm", outdir, label);
    write_ppm(path, px);
    free(px);
}

#define GET(lib, sym) do { *(void **)(&sym) = dlsym(lib, #sym); \
    if (!sym) { fprintf(stderr, "missing %s\n", #sym); return 2; } } while (0)
#define GETGL(sym) do { *(void **)(&sym) = eglGetProcAddress(#sym); \
    if (!sym) { fprintf(stderr, "missing GL %s\n", #sym); return 2; } } while (0)

int main(int argc, char **argv) {
    const char *outdir = argc > 1 ? argv[1] : "/tmp";
    void *egl = dlopen("libEGL.so.1", RTLD_LAZY);
    if (!egl) { fprintf(stderr, "dlopen libEGL: %s\n", dlerror()); return 2; }
    GET(egl, eglGetDisplay); GET(egl, eglInitialize); GET(egl, eglChooseConfig);
    GET(egl, eglBindAPI); GET(egl, eglCreateContext); GET(egl, eglCreatePbufferSurface);
    GET(egl, eglMakeCurrent); GET(egl, eglGetProcAddress);

    EGLDisplay dpy = NULL;
    EGLBoolean (*eglQueryDevicesEXT)(EGLint, void **, EGLint *) = eglGetProcAddress("eglQueryDevicesEXT");
    EGLDisplay (*eglGetPlatformDisplayEXT)(EGLenum, void *, const EGLint *) = eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (eglQueryDevicesEXT && eglGetPlatformDisplayEXT) {
        void *devs[16]; EGLint ndev = 0;
        if (eglQueryDevicesEXT(16, devs, &ndev)) {
            for (EGLint i = 0; i < ndev && !dpy; i++) {
                EGLDisplay d = eglGetPlatformDisplayEXT(0x313F, devs[i], NULL);
                if (d && eglInitialize(d, NULL, NULL)) dpy = d;
            }
        }
    }
    if (!dpy) {
        dpy = eglGetDisplay((void *)0);
        if (!dpy || !eglInitialize(dpy, NULL, NULL)) { fprintf(stderr, "eglInitialize failed\n"); return 2; }
    }
    if (!eglBindAPI(EGL_OPENGL_API)) { fprintf(stderr, "eglBindAPI failed\n"); return 2; }
    EGLint cfgattr[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                         EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE };
    EGLConfig cfg; EGLint ncfg = 0;
    if (!eglChooseConfig(dpy, cfgattr, &cfg, 1, &ncfg) || ncfg < 1) { fprintf(stderr, "no EGL config\n"); return 2; }
    EGLint ctxattr[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                         EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, (EGLContext)0, ctxattr);
    if (!ctx) { fprintf(stderr, "eglCreateContext failed\n"); return 2; }
    EGLint surfattr[] = { EGL_WIDTH, WIDTH, EGL_HEIGHT, HEIGHT, EGL_NONE };
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, surfattr);
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) { fprintf(stderr, "eglMakeCurrent failed\n"); return 2; }

    GETGL(glGenTextures); GETGL(glBindTexture); GETGL(glTexImage2D); GETGL(glTexParameteri);
    GETGL(glTexParameterf); GETGL(glGetFloatv); GETGL(glEnable); GETGL(glClear); GETGL(glClearColor);
    GETGL(glViewport); GETGL(glMatrixMode); GETGL(glLoadIdentity); GETGL(glFrustum); GETGL(glRotatef);
    GETGL(glTranslatef); GETGL(glBegin); GETGL(glEnd); GETGL(glTexCoord2f); GETGL(glVertex3f);
    GETGL(glColor3f); GETGL(glReadPixels); GETGL(glFinish); GETGL(glPixelStorei); GETGL(glGetString);

    printf("GL %s on %s\n", (const char *)glGetString(GL_VERSION), (const char *)glGetString(GL_RENDERER));
    GLfloat maxAniso = 1.0f;
    glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, &maxAniso);
    printf("GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = %.1f\n", maxAniso);

    glViewport(0, 0, WIDTH, HEIGHT);
    build_atlas();
    GLuint tex = upload_atlas();

    printf("atlas %dx%d, %dx%d sprites, %d mip levels, sand at (%d,%d)\n",
           ATLAS, ATLAS, SPRITE, SPRITE, LEVELS - 1, SAND_COL, SAND_ROW);
    run(outdir, "nearestmip_aniso1", tex, GL_NEAREST_MIPMAP_LINEAR, 1.0f);
    run(outdir, "nearestmip_aniso2", tex, GL_NEAREST_MIPMAP_LINEAR, 2.0f);
    run(outdir, "nearestmip_aniso8", tex, GL_NEAREST_MIPMAP_LINEAR, 8.0f);
    run(outdir, "nearestmip_aniso16", tex, GL_NEAREST_MIPMAP_LINEAR, 16.0f);
    run(outdir, "trilinear_aniso1", tex, GL_LINEAR_MIPMAP_LINEAR, 1.0f);
    run(outdir, "trilinear_aniso8", tex, GL_LINEAR_MIPMAP_LINEAR, 8.0f);
    return 0;
}
