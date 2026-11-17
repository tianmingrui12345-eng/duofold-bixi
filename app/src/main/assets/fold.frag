// Third-party notices: assets/THIRD_PARTY_NOTICES.txt
// Full license: THIRD_PARTY_NOTICES.txt. Screen UVs have a top-left origin.
//
// All fold math runs in LEAF space: x centred on the hinge, y = distance from the hinge
// towards the far edge of the moving half. The leaf is always the physical upper half of the
// panel; on a rotated (landscape) display that half is the left or right side, which
// uLandscape/uFlip take care of. Sampling is converted back to display UVs at the end.
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uScreen;
uniform mat4 uTextureMatrix;
uniform vec2 uAngles;
uniform vec2 uSizePoints;
uniform float uIntensity;
uniform float uHeightBias;
uniform float uHeightCurve;   // 0 = linear in fold progress, 1 = 1 - cos(tilt)
uniform float uSideSlant;
uniform float uLandscape;
uniform float uFlip;
uniform int uEffectStyle;
uniform float uHoverBlur;
uniform float uCornerRadius;
uniform float uCornerPower;
uniform float uFoldDegrees; // real fold in degrees; the geometry axis is clamped at 80
// 0.4 fold look, in real fold degrees: dark well before the right angle, the reflection of the
// static half rising past it, and a feather at the hinge so the two halves never meet as a step.
const float DIM_START=60.0;
const float DIM_FULL=88.0;
const float REFL_FULL=118.0;
const float HINGE_FEATHER=45.0;
// Fully closed, the reflection is the same picture as a flat screen: no compression, no blur.
const float CLEAR_FULL=178.0;
varying vec2 vUv;

/**
 * Samples the mirrored frame. The frame is rounded BEFORE anything else happens: the corners
 * are cut to the panel radius, so the frosted sheet inherits rounded corners and keeps them as
 * the sheet moves and shrinks with the fold.
 */
/**
 * Corner coverage of the mirrored frame: a superellipse (squircle) instead of a circular arc,
 * so the curvature is continuous where the corner meets the straight edges (G2). Returns a
 * soft 0..1 mask, which also antialiases the edge.
 */
float cornerMask(vec2 uv) {
    float radius=min(uSizePoints.x,uSizePoints.y)*uCornerRadius;
    vec2 px=uv*uSizePoints;
    vec2 inset=min(px,uSizePoints-px);
    if(inset.x>=radius || inset.y>=radius) return 1.0;
    float u=(radius-inset.x)/radius;
    float v=(radius-inset.y)/radius;
    // Superellipse |u|^n + |v|^n = 1: curvature-continuous (G2), n tunable from the UI.
    float n=max(2.0,uCornerPower);
    float d=pow(pow(u,n)+pow(v,n),1.0/n);
    // Tiny antialias band only; a wide one made the corner look softer than the rest.
    return 1.0-smoothstep(0.995,1.005,d);
}

vec3 readScreen(vec2 uv) {
    if(any(lessThan(uv,vec2(0.0))) || any(greaterThan(uv,vec2(1.0))))return vec3(0.0);
    float mask=cornerMask(uv);
    if(mask<=0.001) return vec3(0.0);
    vec2 st=(uTextureMatrix*vec4(uv.x,1.0-uv.y,0.0,1.0)).xy;
    return texture2D(uScreen,st).rgb*mask;
}
float hash21(vec2 p) {return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);}

// The hinge always sits on the panel's centre line. In landscape that centre line is
// vertical, so the moving half is the left (uFlip 0) or right (uFlip 1) half.
vec2 leafToUv(vec2 q) {
    if(uLandscape<0.5) return vec2(0.5+q.x/uSizePoints.x,0.5-q.y/uSizePoints.y);
    if(uFlip<0.5) return vec2(0.5-q.y/uSizePoints.x,0.5+q.x/uSizePoints.y);
    return vec2(0.5+q.y/uSizePoints.x,0.5+q.x/uSizePoints.y);
}
vec2 uvToLeaf(vec2 uv) {
    if(uLandscape<0.5) return vec2((uv.x-0.5)*uSizePoints.x,(0.5-uv.y)*uSizePoints.y);
    float across=uFlip<0.5?(0.5-uv.x):(uv.x-0.5);
    return vec2((uv.y-0.5)*uSizePoints.y,across*uSizePoints.x);
}

/** Point in leaf space to sample: past the right angle the moving half shows the static half,
 * mirrored across the hinge, and everything else (compression, blur, attenuation) stays the same
 * so the reflection animates exactly like the picture did before the right angle. */
vec2 mirrorPoint(vec2 leafPoint) {
    return uFoldDegrees>DIM_FULL?vec2(leafPoint.x,-leafPoint.y):leafPoint;
}
/**
 * Darkens the moving half as the fold approaches the right angle, then brings the brightness
 * back up on the far side so the reflection rises out of black. Both ends are gradual.
 */
vec3 litFold(vec3 base) {
    float deg=uFoldDegrees;
    float k=deg<=DIM_FULL?1.0-smoothstep(DIM_START,DIM_FULL,deg):smoothstep(DIM_FULL,REFL_FULL,deg);
    return base*k;
}
/**
 * Crossfades the moving half into the static half over the last points before the hinge: the
 * darkened upper half otherwise meets the untouched lower half in a hard line.
 */
vec3 feathered(vec3 colour,vec2 uv,float fromHinge,float settle) {
    // The band closes up as the reflection becomes a true 1:1 mirror, where the seam is already
    // continuous and a fuzzy strip would show as a ghost.
    return mix(readScreen(uv),colour,smoothstep(0.0,HINGE_FEATHER*(1.0-settle)+0.001,fromHinge));
}

void main() {
    float tilt=length(uAngles);
    if(tilt<0.012) {gl_FragColor=vec4(0.0);return;}   // ~0.7 degrees
    // Opaque whenever the layer exists: a fade-in here made the live screen and the mirrored
    // one visible at the same time, which reads as a double image during the first degrees.
    float ramp=1.0;
    // Full blur by ~7 degrees: the effect should be visible as soon as the fold starts.
    float blurRamp=smoothstep(0.02,0.12,tilt);

    float halfLeaf=(uLandscape<0.5?uSizePoints.y:uSizePoints.x)*0.5;
    vec2 p=uvToLeaf(vUv);
    float d=p.y;
    float fromHinge=d;
    if(d<0.0) {
        // Static half: drawn from the same mirrored frame, unblurred and unbent. Painting it
        // here keeps both halves time-aligned; leaving it transparent showed the live panel
        // next to a 1-3 frame old mirror, which read as the upper half lagging behind.
        gl_FragColor=vec4(readScreen(vUv)*ramp,ramp);
        return;
    }

    float gap=d*sin(tilt);
    float depth=1920.0-gap;
    if(depth<=0.00001) {gl_FragColor=vec4(0.0,0.0,0.0,ramp);return;}

    // Height response curve, chosen by the user. Pure cos is flat at 0 degrees while the
    // perspective term (sin) is already shrinking, so the image dips for the first ~8 degrees
    // before the height term catches up. Blending 25% linear into it removes the dip while
    // keeping the cosine's slow start.
    // shortcut: fixed 0.75 blend, expose a slider if anyone wants the raw curve back.
    float linearProgress=clamp(tilt/1.3962634,0.0,1.0);
    float foldProgress=uHeightCurve<0.5
            ? linearProgress
            : mix(linearProgress,1.0-cos(tilt),0.75);
    float vertical=1.0-uHeightBias*foldProgress;
    d*=vertical;
    vec2 hitP=vec2(p.x,d)*(1920.0/depth);
    hitP.x*=1.0+uSideSlant*clamp(d/max(halfLeaf,1.0),0.0,1.0);

    // Past the right angle the two halves come together again, so the reflection loses the
    // compression and the blur and ends up as clear as the flat screen.
    float settle=smoothstep(DIM_FULL,CLEAR_FULL,uFoldDegrees);
    vec2 samplePoint=mix(mirrorPoint(hitP),vec2(p.x,-p.y),settle);

    float radiusPoints=0.12*gap*uIntensity*blurRamp;
    float attenuation=max(1.0-0.015*radiusPoints,0.0);
    if(uEffectStyle==1) {
        float edge=clamp(d/max(halfLeaf,1.0),0.0,1.0);
        float motion=sin(tilt)*uIntensity;
        radiusPoints=36.0*motion*pow(edge,1.35);
        attenuation=max(1.0-2.0*motion*pow(clamp((edge-0.2)/0.8,0.0,1.0),1.35),0.0);
    } else if(uEffectStyle==2) {
        radiusPoints=min(radiusPoints*.65,28.0);
        attenuation=exp(-.007*radiusPoints);
    } else if(uEffectStyle==3) {
        radiusPoints*=uHoverBlur;
        attenuation=max(1.0-.015*radiusPoints,0.0);
    } else if(uEffectStyle==4) {
        radiusPoints=0.0;attenuation=1.0;
    }
    radiusPoints*=(1.0-settle);
    attenuation=mix(attenuation,1.0,settle);

    if(radiusPoints<0.5) {
        gl_FragColor=vec4(feathered(litFold(readScreen(leafToUv(samplePoint))*attenuation),vUv,fromHinge,settle)*ramp,ramp);return;
    }
    float taps=clamp(floor(radiusPoints*3.0),8.0,48.0);
    float rotation=uEffectStyle==2?0.0:hash21(vUv*uSizePoints)*6.28318530718;
    vec3 sum=vec3(0.0);
    float weights=0.0;
    for(int i=0;i<48;i++) {
        if(float(i)>=taps)break;
        float r=sqrt((float(i)+0.5)/taps);
        float a=float(i)*2.39996322973+rotation;
        float weight=uEffectStyle==2?exp(-3.0*r*r):1.0;
        vec2 tap=samplePoint+radiusPoints*r*vec2(cos(a),sin(a));
        sum+=weight*readScreen(leafToUv(tap));
        weights+=weight;
    }
    gl_FragColor=vec4(feathered(litFold(sum/weights*attenuation),vUv,fromHinge,settle)*ramp,ramp);
}
