#version 120

// Panini projection, ported from Sodium Extra's post/panini.fsh to 1.12.2's GLSL 120 post pipeline.
//
// A Panini projection is cylindrical horizontally and rectilinear vertically, which is what lets a
// wide field of view avoid the corner stretching a plain perspective projection produces past about
// 100 degrees. This is a screen-space remap of the finished frame, so it can only redistribute what
// was already rendered — it widens nothing that the perspective projection did not draw.
//
// Lives in the minecraft namespace because 1.12.2's ShaderLoader hard-codes it when resolving
// program names; the impetus_ prefix is what keeps it from colliding.

uniform sampler2D DiffuseSampler;

// x: strength (0 = off, 1 = full)
// y: horizontal half-extent of the source frustum at unit depth
// z: vertical half-extent of the source frustum at unit depth
uniform vec4 PaniniParams;

varying vec2 texCoord;

void main() {
    float paniniD = clamp(PaniniParams.x, 0.0, 1.0);
    vec2 sourceExtent = max(PaniniParams.yz, vec2(0.0001));

    // Scale the lens from the actual perspective projection, so the horizontal field of view stays
    // put across FOV effects and aspect ratios instead of breathing with them.
    float cosLonEdge = inversesqrt(1.0 + sourceExtent.x * sourceExtent.x);
    float edgeScale = (paniniD + 1.0) / (paniniD + cosLonEdge);
    float fitScale = max(edgeScale * cosLonEdge, 0.0001);

    vec2 ndc = texCoord * 2.0 - 1.0;
    vec2 paniniPlane = ndc * sourceExtent * fitScale;

    float dPlusOne = paniniD + 1.0;
    float normalizedX = paniniPlane.x / dPlusOne;
    float normalizedX2 = normalizedX * normalizedX;
    float discriminant = 1.0 + normalizedX2 * (1.0 - paniniD * paniniD);
    float cosLon = (-normalizedX2 * paniniD + sqrt(max(0.0, discriminant))) / (normalizedX2 + 1.0);
    float paniniScale = dPlusOne / (paniniD + cosLon);
    float projectionDivisor = max(paniniScale * cosLon, 0.0001);

    vec2 sourceNdc = paniniPlane / (projectionDivisor * sourceExtent);
    vec2 sourceUv = clamp(sourceNdc * 0.5 + 0.5, 0.0, 1.0);

    gl_FragColor = texture2D(DiffuseSampler, sourceUv);
}
