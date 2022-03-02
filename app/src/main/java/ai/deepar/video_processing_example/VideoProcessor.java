package ai.deepar.video_processing_example;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import ai.deepar.ar.ARErrorType;
import ai.deepar.ar.AREventListener;
import ai.deepar.ar.DeepAR;
import ai.deepar.ar.DeepARImageFormat;

public class VideoProcessor {

    private static final String TAG = VideoProcessor.class.getSimpleName();

    private static final String VIDEO_MIME = "video/avc";
    private static final float BPP = 0.15f;
    private static final int I_FRAME_INTERVAL = 5;

    private final Context context;
    private final Handler handler;
    private final Handler mainHandler;
    private final Consumer<Double> onStep;
    private final Runnable onSuccess;

    private MediaExtractor mediaExtractor;
    private MediaCodec mediaDecoder;
    private MediaCodec mediaEncoder;
    private MediaMuxer mediaMuxer;

    private MediaFormat inputMediaFormat;
    private int width;
    private int height;
    private int rotation;
    private int rotatedWidth;
    private int rotatedHeight;
    private float videoDurationUs;
    private float frameRate;
    private int muxerTrackIndex;

    private DeepAR deepAR;
    private Surface encoderInputSurface;

    private ByteBuffer buffer;
    private final Queue<Long> timestampQueue = new ArrayDeque<>();

    public VideoProcessor(Context context, Handler handler, Consumer<Double> onStep, Runnable onSuccess) {
        this.context = context;
        this.handler = handler;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.onStep = onStep;
        this.onSuccess = onSuccess;
    }

    public void processVideo(Uri videoUri, File outputFile, String effectPath) {
        handler.post(() -> {
            try {
                setupMediaExtractor(videoUri);
                setupMediaDecoder();
                setupMediaEncoder();
                setupMediaMuxer(outputFile);
            } catch (IOException e) {
                Log.e(TAG, "processVideo: IOException occurred", e);
                return;
            }
            buffer = ByteBuffer.allocateDirect(width * height * 3);
            setupDeepAR(effectPath);
        });
    }

    private void setupMediaExtractor(Uri videoUri) throws IOException {
        mediaExtractor = new MediaExtractor();
        mediaExtractor.setDataSource(context, videoUri, null);

        int videoTrackIndex = findVideoTrack(mediaExtractor);
        if (videoTrackIndex < 0) {
            throw new IllegalArgumentException(VIDEO_MIME + " track not found");
        }
        mediaExtractor.selectTrack(videoTrackIndex);

        inputMediaFormat = mediaExtractor.getTrackFormat(videoTrackIndex);
        width = inputMediaFormat.getInteger(MediaFormat.KEY_WIDTH);
        height = inputMediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
        try {
            rotation = inputMediaFormat.getInteger(MediaFormat.KEY_ROTATION);
        } catch (Exception e) {
            rotation = 0;
        }
        rotatedWidth = rotation == 0 || rotation == 180 ? width : height;
        rotatedHeight = rotation == 0 || rotation == 180 ? height : width;
        videoDurationUs = inputMediaFormat.getLong(MediaFormat.KEY_DURATION);
        try {
            frameRate = inputMediaFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
        } catch (ClassCastException e) {
            frameRate = inputMediaFormat.getFloat(MediaFormat.KEY_FRAME_RATE);
        }
    }

    private void setupMediaDecoder() throws IOException {
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        Log.d(TAG, "supported decoders: " + getSupportedDecoderNames(mediaCodecList));

        String decoderName = mediaCodecList.findDecoderForFormat(inputMediaFormat);
        mediaDecoder = MediaCodec.createByCodecName(decoderName);
        mediaDecoder.configure(inputMediaFormat, null, null, 0);
        mediaDecoder.setCallback(new MediaDecoderCallback(), handler);
    }

    private void setupMediaEncoder() throws IOException {
        mediaEncoder = MediaCodec.createEncoderByType(VIDEO_MIME);
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, rotatedWidth, rotatedHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, (int) (BPP * frameRate * width * height));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, (int) frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        mediaEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mediaEncoder.setCallback(new MediaEncoderCallback(), handler);
    }

    private void setupMediaMuxer(File outputFile) throws IOException {
        mediaMuxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        // Video track is added later (once encoder's output format has been fully configured).
    }

    private void setupDeepAR(String effectPath) {
        deepAR = new DeepAR(context);
        deepAR.setLicenseKey("your_license_key_goes_here");
        deepAR.initialize(context, new AREventListener() {
            @Override
            public void screenshotTaken(Bitmap bitmap) {
            }

            @Override
            public void videoRecordingStarted() {
            }

            @Override
            public void videoRecordingFinished() {
            }

            @Override
            public void videoRecordingFailed() {
            }

            @Override
            public void videoRecordingPrepared() {
            }

            @Override
            public void shutdownFinished() {
            }

            @Override
            public void initialized() {
                deepAR.changeLiveMode(false);
                deepAR.switchEffect("mask", "file:///android_asset/" + effectPath);

                mediaEncoder.start();
                mediaDecoder.start();
            }

            @Override
            public void faceVisibilityChanged(boolean b) {
            }

            @Override
            public void imageVisibilityChanged(String s, boolean b) {
            }

            @Override
            public void frameAvailable(Image image) {
            }

            @Override
            public void error(ARErrorType arErrorType, String s) {
            }

            @Override
            public void effectSwitched(String s) {
            }
        });

        encoderInputSurface = mediaEncoder.createInputSurface();
        deepAR.useSingleThreadedMode(false); // Calls to receiveFrame will be blocking.
        deepAR.setRenderSurface(encoderInputSurface, rotatedWidth, rotatedHeight);
    }

    private int findVideoTrack(MediaExtractor mediaExtractor) {
        for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {
            MediaFormat mediaFormat = mediaExtractor.getTrackFormat(i);
            if (mediaFormat.getString(MediaFormat.KEY_MIME).equalsIgnoreCase(VIDEO_MIME)) {
                return i;
            }
        }
        return -1;
    }

    private List<String> getSupportedDecoderNames(MediaCodecList mediaCodecList) {
        List<String> supportedDecoderNames = new ArrayList<>();
        for (MediaCodecInfo codecInfo : mediaCodecList.getCodecInfos()) {
            if (!codecInfo.isEncoder() && codecSupportsVideoMime(codecInfo)) {
                supportedDecoderNames.add(codecInfo.getName());
            }
        }
        return supportedDecoderNames;
    }

    private boolean codecSupportsVideoMime(MediaCodecInfo codecInfo) {
        for (String type : codecInfo.getSupportedTypes()) {
            if (type.equalsIgnoreCase(VIDEO_MIME)) {
                return true;
            }
        }
        return false;
    }

    private void processFrame(Image image) {
        byte[] byteData;
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byteData = new byte[ySize + uSize + vSize];

        // U and V are swapped
        yBuffer.get(byteData, 0, ySize);
        vBuffer.get(byteData, ySize, vSize);
        uBuffer.get(byteData, ySize + vSize, uSize);

        buffer.order(ByteOrder.nativeOrder());
        buffer.position(0);
        buffer.put(byteData);
        buffer.position(0);

        deepAR.receiveFrame(buffer, image.getWidth(), image.getHeight(), rotation, false, DeepARImageFormat.YUV_420_888, image.getPlanes()[1].getPixelStride());

        // An alternative approach would be to set up media decoder with a Surface created from an
        // external GL texture and call deepAR.receiveFrameExternalTexture() in the corresponding
        // SurfaceTexture's OnFrameAvailableListener. This is usually the preferred way of feeding
        // frames to DeepAR as it is quite efficient. However, MediaCodec seems to be dropping
        // frames when configured with a Surface, so we are using ByteBuffer input instead.
    }

    private void cleanUp() {
        mediaExtractor.release();
        mediaDecoder.release();
        mediaMuxer.release();
        mediaEncoder.release();
        inputMediaFormat = null;
        timestampQueue.clear();
        encoderInputSurface.release();
        deepAR.setAREventListener(null);
        deepAR.release();
    }

    private class MediaDecoderCallback extends MediaCodec.Callback {

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            ByteBuffer inputBuffer = codec.getInputBuffer(index);
            if (inputBuffer == null) {
                return;
            }

            int size = mediaExtractor.readSampleData(inputBuffer, 0);
            if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                return;
            }

            long presentationTime = mediaExtractor.getSampleTime();
            boolean endOfStream = !mediaExtractor.advance();
            codec.queueInputBuffer(index, 0, size, presentationTime, endOfStream ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                Log.d(TAG, "onOutputBufferAvailable: end of decoder stream; signalling end of stream to encoder");
                mediaEncoder.signalEndOfInputStream();
            }

            Image image = codec.getOutputImage(index);
            if (image == null) {
                return;
            }
            processFrame(image);
            image.close();

            timestampQueue.offer(info.presentationTimeUs);
            codec.releaseOutputBuffer(index, true);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.e(TAG, "onError", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            // Do nothing.
        }
    }

    private class MediaEncoderCallback extends MediaCodec.Callback {

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            // Not called.
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            ByteBuffer outputBuffer = codec.getOutputBuffer(index);
            if (outputBuffer == null) {
                return;
            }

            if (info.flags != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                Long timestamp = timestampQueue.poll();
                if (timestamp == null) {
                    throw new IllegalStateException("Encountered null timestamp but end of stream not yet reached");
                } else {
                    info.presentationTimeUs = timestamp;
                }
            }

            mediaMuxer.writeSampleData(muxerTrackIndex, outputBuffer, info);
            codec.releaseOutputBuffer(index, false);

            double progress = (double) (info.presentationTimeUs * 1000L / videoDurationUs) / 10.0;
            mainHandler.post(() -> onStep.accept(progress));

            if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                handler.post(() -> {
                    cleanUp();
                    mainHandler.post(onSuccess);
                });
            }
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.e(TAG, "onError", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            format.setInteger(MediaFormat.KEY_WIDTH, rotatedWidth);
            format.setInteger(MediaFormat.KEY_HEIGHT, rotatedHeight);
            muxerTrackIndex = mediaMuxer.addTrack(format);
            mediaMuxer.start();
        }
    }
}
