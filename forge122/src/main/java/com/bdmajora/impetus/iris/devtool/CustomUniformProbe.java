package com.bdmajora.impetus.iris.devtool;

import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import com.bdmajora.impetus.iris.shaderpack.ShaderPackLoader;
import com.bdmajora.impetus.iris.uniforms.custom.CustomUniformInputs;
import com.bdmajora.impetus.iris.uniforms.custom.CustomUniformValue;
import com.bdmajora.impetus.iris.uniforms.custom.CustomUniforms;

import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Headless probe for the shaders.properties custom-uniform evaluator: loads a pack, overrides the built-in
 * uniform inputs with a plausible mid-morning scene, evaluates every custom uniform/variable for a few frames
 * (so {@code smooth()} converges), and prints the results. Catches evaluator bugs (wrong function semantics,
 * broken chaining) without launching the game.
 * <p>
 * Usage: {@code CustomUniformProbe <pack.zip> [name=value ...]}
 */
public final class CustomUniformProbe {
    private CustomUniformProbe() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        ShaderPack pack = ShaderPackLoader.loadFromZip(Paths.get(args[0]));
        CustomUniforms customUniforms = pack.getProperties().getCustomUniforms().build();

        // Reflect into the inputs registry and install daytime scene values over the live-game suppliers.
        Field inputsField = CustomUniforms.class.getDeclaredField("inputs");
        inputsField.setAccessible(true);
        CustomUniformInputs inputs = (CustomUniformInputs) inputsField.get(customUniforms);
        Field mapField = CustomUniformInputs.class.getDeclaredField("inputs");
        mapField.setAccessible(true);
        Map<String, Supplier<CustomUniformValue>> map =
                (Map<String, Supplier<CustomUniformValue>>) mapField.get(inputs);

        put(map, "sunAngle", 0.08f);
        put(map, "celestialAngle", 0.83f);
        put(map, "shadowAngle", 0.08f);
        put(map, "worldTime", 2000);
        put(map, "worldDay", 1);
        put(map, "moonPhase", 0);
        put(map, "frameTimeCounter", 100.0f);
        put(map, "frameCounter", 100);
        put(map, "rainStrength", 0.0f);
        put(map, "wetness", 0.0f);
        put(map, "isEyeInWater", 0);
        put(map, "far", 256.0f);
        put(map, "near", 0.05f);
        put(map, "eyeAltitude", 80.0f);
        put(map, "nightVision", 0.0f);
        put(map, "blindness", 0.0f);
        put(map, "darknessFactor", 0.0f);
        put(map, "screenBrightness", 1.0f);
        put(map, "hideGUI", 0);
        map.put("cameraPosition", () -> CustomUniformValue.of(100.5f, 80.0f, -200.5f));
        map.put("previousCameraPosition", () -> CustomUniformValue.of(100.4f, 80.0f, -200.4f));
        map.put("sunPosition", () -> CustomUniformValue.of(0.0f, 95.0f, -25.0f));
        map.put("moonPosition", () -> CustomUniformValue.of(0.0f, -95.0f, 25.0f));
        map.put("shadowLightPosition", () -> CustomUniformValue.of(0.0f, 95.0f, -25.0f));
        map.put("upPosition", () -> CustomUniformValue.of(0.0f, 100.0f, 0.0f));
        map.put("skyColor", () -> CustomUniformValue.of(0.45f, 0.65f, 0.95f));
        map.put("fogColor", () -> CustomUniformValue.of(0.6f, 0.7f, 0.9f));
        map.put("eyeBrightnessSmooth", () -> CustomUniformValue.of(240.0f, 240.0f));
        map.put("eyeBrightness", () -> CustomUniformValue.of(240.0f, 240.0f));

        // Extra overrides from the command line.
        for (int i = 1; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            float v = Float.parseFloat(kv[1]);
            map.put(kv[0], () -> CustomUniformValue.scalar(v));
        }

        // Evaluate several frames so smooth() converges toward the inputs.
        for (int frame = 0; frame < 600; frame++) {
            customUniforms.update();
        }

        Field variablesField = CustomUniforms.class.getDeclaredField("variables");
        variablesField.setAccessible(true);
        java.util.List<?> variables = (java.util.List<?>) variablesField.get(customUniforms);
        for (Object variable : variables) {
            Field nameField = variable.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            Field currentField = variable.getClass().getDeclaredField("current");
            currentField.setAccessible(true);
            CustomUniformValue value = (CustomUniformValue) currentField.get(variable);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < value.width; i++) {
                if (i > 0) {
                    s.append(", ");
                }
                s.append(String.format("%.4f", value.components[i]));
            }
            System.out.println(nameField.get(variable) + " = (" + s + ")");
        }
    }

    private static void put(Map<String, Supplier<CustomUniformValue>> map, String name, float value) {
        map.put(name, () -> CustomUniformValue.scalar(value));
    }
}
