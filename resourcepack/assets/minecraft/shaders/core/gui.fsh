#version 330

// Plain colour fills in menus (Minecraft 1.21.10 core shader "gui").
// Same as vanilla, except pure opaque white fills are skipped: that is the colour of the
// focus outline the client draws around clicked dialog text, which flashes around the
// instrument keyboard after every note. Other pure white menu fills are rare.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor;
    if (color.a == 0.0 || all(greaterThanEqual(color, vec4(0.999)))) {
        discard;
    }
    fragColor = color * ColorModulator;
}
