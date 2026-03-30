// SPDX-FileCopyrightText: Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause

#version 460 core

//precision mediump float;
//precision highp int;
#define highp
#define mediump

// Operation modes: RGBA -> 1, RGBY -> 3, LERP -> 4
#define OperationMode 1
#define EdgeThreshold 8.0/255.0
// #define EdgeSharpness 2.0
// #define UseUniformBlock

#if defined(UseUniformBlock)
layout( push_constant ) uniform constants {
    highp vec4 ViewportInfo[1];
    highp vec2 ResizeFactor;
    highp float EdgeSharpness;
};
layout(set = 0, binding = 0) uniform mediump sampler2D ps0;
#else
uniform highp vec4 ViewportInfo[1];
uniform mediump sampler2D ps0;
#endif

layout(location=0) in highp vec2 in_TEXCOORD0;
layout(location=0) out vec4 out_Target0;

float fastLanczos2(float x) {
    float wA = x-4.0;
    float wB = x*wA-wA;
    wA *= wA;
    return wB*wA;
}

vec2 weightY(float dx, float dy,float c, float std) {
    float x = ((dx*dx)+(dy* dy))* 0.55 + clamp(abs(c)*std, 0.0, 1.0);
    float w = fastLanczos2(x);
    return vec2(w, w * c);
}

void main() {
    vec4 color;
    if(OperationMode == 1)
        color.xyz = textureLod(ps0, in_TEXCOORD0.xy, 0.0).xyz;
    else
        color.xyzw = textureLod(ps0, in_TEXCOORD0.xy, 0.0).xyzw;

    highp float xCenter = abs(in_TEXCOORD0.x + -0.5);
    highp float yCenter = abs(in_TEXCOORD0.y + -0.5);

    //todo: config the SR region based on needs
    //if ( OperationMode!=4 && xCenter*xCenter+yCenter*yCenter<=0.4 * 0.4)
    if ( OperationMode!=4) {
        highp vec2 imgCoord = ((in_TEXCOORD0.xy * ViewportInfo[0].zw) + vec2(-0.5, 0.5));
        highp vec2 imgCoordPixel = floor(imgCoord);
        highp vec2 coord = imgCoordPixel * ViewportInfo[0].xy;
        vec2 pl = imgCoord - imgCoordPixel;
        vec4 left = textureGather(ps0, coord, OperationMode);
        float edgeVote = abs(left.z - left.y) + abs(color[OperationMode] - left.y)  + abs(color[OperationMode] - left.z) ;
        if(edgeVote > EdgeThreshold) {
            coord.x += ViewportInfo[0].x;

            highp vec2 IR_highp_vec2_0 = coord + vec2(ViewportInfo[0].x, 0.0);
            vec4 right = textureGather(ps0, IR_highp_vec2_0, OperationMode);
            vec4 upDown;
            highp vec2 IR_highp_vec2_1 = coord + vec2(0.0, -ViewportInfo[0].y);
            upDown.xy = textureGather(ps0, IR_highp_vec2_1, OperationMode).wz;
            highp vec2 IR_highp_vec2_2 = coord + vec2(0.0, ViewportInfo[0].y);
            upDown.zw  = textureGather(ps0, IR_highp_vec2_2, OperationMode).yx;

            float mean = (left.y+left.z+right.x+right.w)*0.25;
            left = left - vec4(mean);
            right = right - vec4(mean);
            upDown = upDown - vec4(mean);
            color.w =color[OperationMode] - mean;

            float sum = (((((abs(left.x)+abs(left.y))+abs(left.z))+abs(left.w))+(((abs(right.x)+abs(right.y))+abs(right.z))+abs(right.w)))+(((abs(upDown.x)+abs(upDown.y))+abs(upDown.z))+abs(upDown.w)));
            float std = 2.181818/sum;

            vec2 aWY = weightY(pl.x, pl.y+1.0, upDown.x,std);
            aWY += weightY(pl.x-1.0, pl.y+1.0, upDown.y,std);
            aWY += weightY(pl.x-1.0, pl.y-2.0, upDown.z,std);
            aWY += weightY(pl.x, pl.y-2.0, upDown.w,std);
            aWY += weightY(pl.x+1.0, pl.y-1.0, left.x,std);
            aWY += weightY(pl.x, pl.y-1.0, left.y,std);
            aWY += weightY(pl.x, pl.y, left.z,std);
            aWY += weightY(pl.x+1.0, pl.y, left.w,std);
            aWY += weightY(pl.x-1.0, pl.y-1.0, right.x,std);
            aWY += weightY(pl.x-2.0, pl.y-1.0, right.y,std);
            aWY += weightY(pl.x-2.0, pl.y, right.z,std);
            aWY += weightY(pl.x-1.0, pl.y, right.w,std);

            float finalY = aWY.y/aWY.x;

            float maxY = max(max(left.y,left.z),max(right.x,right.w));
            float minY = min(min(left.y,left.z),min(right.x,right.w));
            finalY = clamp(EdgeSharpness*finalY, minY, maxY);

            float deltaY = finalY -color.w;

            //smooth high contrast input
            deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);

            color.x = clamp((color.x+deltaY),0.0,1.0);
            color.y = clamp((color.y+deltaY),0.0,1.0);
            color.z = clamp((color.z+deltaY),0.0,1.0);
        }
    }

    color.w = 1.0;  //assume alpha channel is not used
    out_Target0.xyzw = color;
}