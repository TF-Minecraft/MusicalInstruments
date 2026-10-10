#version 330

#moj_import <minecraft:globals.glsl>

// Menu blur (vanilla box blur) that is skipped while the instrument keyboard is open.
// The server shows a title with a one-GUI-pixel dot in the exact MARKER colour on the screen's
// centre pixel (font tfmc_instruments:keyboard, U+E3F0). If a pixel next to the centre has that
// colour the image is passed through unblurred, and the final pass paints over the dot.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BlurConfig {
    vec2 BlurDir;
    float Radius;
};

layout(std140) uniform KeyboardConfig {
    float Final;
};

in vec2 texCoord;

out vec4 fragColor;

// The marker is drawn as text, and text is multiplied by the lightmap colour, which is not
// exactly white. Its colour is (0, 0, 24): red and green stay 0, blue scales a little.
bool isMarker(vec4 colour) {
    ivec3 c = ivec3(round(colour.rgb * 255.0));
    return c.r == 0 && c.g == 0 && c.b >= 8 && c.b <= 48;
}

// The dot is one GUI pixel (GUI scale 1-6 screen pixels); look in a 3x3 patch at the centre.
bool markerAtCentre() {
    ivec2 centre = ivec2(InSize * 0.5);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            if (isMarker(texelFetch(InSampler, centre + ivec2(x, y), 0))) {
                return true;
            }
        }
    }
    return false;
}

void main() {
    if (markerAtCentre()) {
        ivec2 pixel = ivec2(gl_FragCoord.xy);
        vec4 here = texelFetch(InSampler, pixel, 0);
        if (Final > 0.5 && isMarker(here)) {
            // Take the colour from just beside the dot (at most 6 px wide).
            here = texelFetch(InSampler, pixel + ivec2(8, 0), 0);
        }
        fragColor = here;
        return;
    }

    // Vanilla box blur (minecraft:post/box_blur).
    vec2 oneTexel = 1.0 / InSize;
    vec2 sampleStep = oneTexel * BlurDir;

    vec4 blurred = vec4(0.0);
    float actualRadius = Radius >= 0.5 ? round(Radius) : float(MenuBlurRadius);
    for (float a = -actualRadius + 0.5; a <= actualRadius; a += 2.0) {
        blurred += texture(InSampler, texCoord + sampleStep * a);
    }
    blurred += texture(InSampler, texCoord + sampleStep * actualRadius) / 2.0;
    fragColor = blurred / (actualRadius + 0.5);
}
