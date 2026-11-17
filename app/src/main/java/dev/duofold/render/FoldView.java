package dev.duofold.render;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import dev.duofold.motion.FoldModel;
import dev.duofold.motion.EffectStyle;
import dev.duofold.motion.HoverBlur;

public final class FoldView extends android.opengl.GLSurfaceView implements android.opengl.GLSurfaceView.Renderer {
    public interface Listener {
        void onReady(Surface input,int width,int height);
        void onFirstFrame();
        void onError(String message);
    }
    private final Listener listener;
    private final FloatBuffer quad=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] matrix=new float[16];
    private volatile FoldModel.State state=FoldModel.atAxes(0,0,1,400,800);
    private volatile FoldModel.State drawnState=FoldModel.atAxes(0,0,1,400,800);
    private volatile EffectStyle effectStyle=EffectStyle.CLASSIC;
    private EffectStyle drawnStyle=EffectStyle.CLASSIC;
    private final HoverBlur hoverBlur=new HoverBlur();
    private float displayedHover=1;
    private long lastDrawNanos;
    private SurfaceTexture texture;
    private Surface input;
    private int program,textureId,width,height;
    private int frameLog;
    private int captureWidth,captureHeight;
    private volatile boolean newFrame,hasFrame;
    private boolean firstFrameReported;
    private volatile boolean stopped;
    private final Runnable settleFrame=()-> {if(!stopped)requestRender();};
    public FoldView(Context context,Listener listener) {
        super(context); this.listener=listener;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8,8,8,8,0,0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setPreserveEGLContextOnPause(true);
        quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
        setRenderer(this); setRenderMode(RENDERMODE_WHEN_DIRTY);
    }
    /** Low-pass time constant: long enough to hide the hinge's 1-degree steps, short enough to
     *  feel attached to the hinge. 150 ms felt laggy, 70 ms still slightly heavy; 60 ms now. */
    private static final float SMOOTH_TAU=0.06f;
    private volatile float targetForward,targetSide,targetIntensity=1f;
    private volatile float smoothForward;
    private float smoothSide;
    private long smoothNanos;

    /** The angle actually being rendered, in degrees of fold (after the low-pass). */
    public float smoothedDegrees() {return (float)Math.toDegrees(smoothForward);}

    /** Called on every sensor event; the value is smoothed per frame before rendering. */
    public void setFold(float forwardRadians,float sideRadians,float intensity) {
        targetForward=forwardRadians;targetSide=sideRadians;targetIntensity=intensity;
        requestRender();
    }
    public void setEffectStyle(EffectStyle style) {
        effectStyle=style==null?EffectStyle.CLASSIC:style;requestRender();
    }
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config) {
        try {
            program=GLES20.glCreateProgram();
            GLES20.glAttachShader(program,compile(GLES20.GL_VERTEX_SHADER,asset("fold.vert")));
            GLES20.glAttachShader(program,compile(GLES20.GL_FRAGMENT_SHADER,asset("fold.frag")));
            GLES20.glLinkProgram(program);
            int[] status=new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0);
            if(status[0]==0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            int[] ids=new int[1]; GLES20.glGenTextures(1,ids,0); textureId=ids[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            texture=new SurfaceTexture(textureId);
            texture.setOnFrameAvailableListener(t->{newFrame=true; requestRender();});
            input=new Surface(texture); hasFrame=false;
        } catch(Exception e) { post(()->listener.onError("GPU: "+e.getMessage())); }
    }
    @Override public void onSurfaceChanged(GL10 gl,int w,int h) {
        GLES20.glViewport(0,0,w,h); width=w; height=h;
        if(texture==null || stopped) return;
        // Native resolution by default; a smaller buffer shortens capture latency.
        int requested=dev.duofold.EffectPrefs.captureWidth;
        captureWidth=requested<=0?w:Math.min(w,requested);
        captureHeight=Math.round(h*(captureWidth/(float)w));
        texture.setDefaultBufferSize(captureWidth,captureHeight);
        post(()-> { if(!stopped) listener.onReady(input,captureWidth,captureHeight); });
    }
    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClearColor(0,0,0,0); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if(texture==null || stopped || program==0) return;
        try {
            if(newFrame) { newFrame=false; texture.updateTexImage(); texture.getTransformMatrix(matrix); hasFrame=true; }
            if(!hasFrame) return;
            EffectStyle style=effectStyle;
            // ~150 ms low-pass: the hinge reports whole degrees, so without this the start of
            // the fold jumps straight from 0 to 2 degrees instead of easing in.
            if(smoothNanos==0) {smoothForward=targetForward;smoothSide=targetSide;}
            long smoothNow=System.nanoTime();
            float sdt=smoothNanos==0?0f:Math.min(0.1f,(smoothNow-smoothNanos)/1e9f);
            smoothNanos=smoothNow;
            float k=sdt<=0f?1f:1f-(float)Math.exp(-sdt/SMOOTH_TAU);
            smoothForward+=(targetForward-smoothForward)*k;
            smoothSide+=(targetSide-smoothSide)*k;
            boolean settling=Math.abs(targetForward-smoothForward)>0.0005f
                    || Math.abs(targetSide-smoothSide)>0.0005f;
            if(!settling) {smoothForward=targetForward;smoothSide=targetSide;}
            else postDelayed(this::requestRender,16);
            float density=getResources().getDisplayMetrics().density;
            FoldModel.State s=FoldModel.atAxes(smoothForward,smoothSide,targetIntensity,
                    Math.max(1,width)/density,Math.max(1,height)/density);
            state=s;
            long now=System.nanoTime();
            if(style!=drawnStyle) {hoverBlur.reset();displayedHover=1;lastDrawNanos=0;drawnStyle=style;}
            float targetHover=style==EffectStyle.SETTLE?hoverBlur.sample(s.angleX,s.angleY,now/1e9):1;
            float dt=lastDrawNanos==0?0:Math.min(.1f,(now-lastDrawNanos)/1e9f);
            lastDrawNanos=now;
            // Fade back into blur on movement instead of snapping from a clear held frame.
            float response=1-(float)Math.exp(-dt/.045f);
            displayedHover+=response*(targetHover-displayedHover);
            if(Math.abs(displayedHover-targetHover)<.001f)displayedHover=targetHover;
            {
            GLES20.glUseProgram(program);
            int pos=GLES20.glGetAttribLocation(program,"aPosition");
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,quad);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
            GLES20.glUniform1i(location("uScreen"),0);
            GLES20.glUniformMatrix4fv(location("uTextureMatrix"),1,false,matrix,0);
            GLES20.glUniform2f(location("uAngles"),s.angleX,s.angleY);
            GLES20.glUniform2f(location("uSizePoints"),s.widthPoints,s.heightPoints);
            scalar("uIntensity",s.intensity);
            scalar("uHeightBias",dev.duofold.EffectPrefs.heightBias);
            scalar("uSideSlant",dev.duofold.EffectPrefs.sideSlant);
            scalar("uHeightCurve",dev.duofold.EffectPrefs.heightCurve);
            scalar("uCornerRadius",dev.duofold.EffectPrefs.cornerRadius);
            scalar("uCornerPower",dev.duofold.EffectPrefs.cornerPower);
            // Landscape: the hinge line turns vertical, so the leaf is the left (rotation 90)
            // or right (rotation 270) half of the panel.
            boolean landscape=width>height;
            int displayRotation=getDisplay()==null?Surface.ROTATION_0:getDisplay().getRotation();
            scalar("uLandscape",landscape?1f:0f);
            scalar("uFlip",displayRotation==Surface.ROTATION_270?1f:0f);
            GLES20.glUniform1i(location("uEffectStyle"),style.shaderId);
            scalar("uHoverBlur",displayedHover);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            GLES20.glDisableVertexAttribArray(pos);
            }
            drawnState=s;
            removeCallbacks(settleFrame);
            if(style==EffectStyle.SETTLE && (targetHover>0 || displayedHover>0))postDelayed(settleFrame,16);
            if(!firstFrameReported) {
                firstFrameReported=true;
                post(listener::onFirstFrame);
            }
        } catch(Exception e) { post(()->listener.onError("GPU frame: "+e.getMessage())); }
    }
    public void releaseResources() {
        stopped=true;
        removeCallbacks(settleFrame);
        queueEvent(()-> {
            if(input!=null) {input.release();input=null;}
            if(texture!=null) {texture.release();texture=null;}
            if(program!=0) GLES20.glDeleteProgram(program);
            if(textureId!=0) GLES20.glDeleteTextures(1,new int[]{textureId},0);
        });
    }
    private int createProgram(String vertex,String fragment) throws Exception {
        int result=GLES20.glCreateProgram();
        int vs=compile(GLES20.GL_VERTEX_SHADER,asset(vertex)),fs=compile(GLES20.GL_FRAGMENT_SHADER,asset(fragment));
        GLES20.glAttachShader(result,vs);GLES20.glAttachShader(result,fs);GLES20.glLinkProgram(result);
        GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);
        int[] status=new int[1];GLES20.glGetProgramiv(result,GLES20.GL_LINK_STATUS,status,0);
        if(status[0]==0) {String info=GLES20.glGetProgramInfoLog(result);GLES20.glDeleteProgram(result);throw new IllegalStateException(info);}
        return result;
    }
    private int location(String name) { return GLES20.glGetUniformLocation(program,name); }
    private void scalar(String name,float value) { GLES20.glUniform1f(location(name),value); }
    private String asset(String name) throws Exception {
        try(InputStream in=getContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int read;
            while((read=in.read(buffer))!=-1)out.write(buffer,0,read);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private int compile(int type,String source) {
        int shader=GLES20.glCreateShader(type); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader);
        int[] status=new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0);
        if(status[0]==0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader)); return shader;
    }
}
