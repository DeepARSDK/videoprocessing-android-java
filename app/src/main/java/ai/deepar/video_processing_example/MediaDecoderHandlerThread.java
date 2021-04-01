

package ai.deepar.video_processing_example;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import android.annotation.SuppressLint;
import android.content.ContextWrapper;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import ai.deepar.ar.DeepAR;
import ai.deepar.ar.DeepARImageFormat;

public class MediaDecoderHandlerThread extends HandlerThread {
    private MediaExtractor extractor = new MediaExtractor();
    private MediaCodec decoder;

    private boolean end_of_input_file;
    private int outputBufferIndex = -1;

    private int width = -1;
    private int height = -1;

    private ByteBuffer[] buffers;
    private int currentBuffer = 0;
    private static final int NUMBER_OF_BUFFERS= 2;

    private Handler handler;
    private DeepAR imageReceiver;
    private WeakReference<ContextWrapper> mContext;

    public MediaDecoderHandlerThread(ContextWrapper context) {
        super("ExampleHandlerThread", Process.THREAD_PRIORITY_BACKGROUND);
        this.mContext = new WeakReference<ContextWrapper>(context);
    }

    public Handler getHandler() {
        return handler;
    }

    protected void onLooperPrepared() {
        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (decoder != null && extractor != null) {
                    decoder.stop();
                    decoder.release();
                    extractor.release();
                    decoder = null;
                }
                setFilenameAndStart((String) msg.obj);
            }
        };
    }

    synchronized void setImageReceiver(DeepAR receiver) {
        this.imageReceiver = receiver;
    }

    void setFilenameAndStart(String inputFilename) {
        extractor = new MediaExtractor();
        end_of_input_file = false;
        try {
            extractor.setDataSource(inputFilename);

            // Select the first audio track we find.
            int numTracks = extractor.getTrackCount();
            for (int i = 0; i < numTracks; ++i) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    width = format.getInteger(MediaFormat.KEY_WIDTH);
                    height = format.getInteger(MediaFormat.KEY_HEIGHT);
                    extractor.selectTrack(i);
                    decoder = MediaCodec.createDecoderByType(mime);
                    decoder.configure(format, null, null, 0);
                    decoder.start();
                    BufferInfo info = new BufferInfo();
                    readData(info);
                    break;
                }
            }
            if (decoder == null) {
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        end_of_input_file = false;
    }

    // Read the data from MediaCodec and feed it into DeepAR frame by frame.
    @SuppressLint("WrongConstant")
    private void readData(BufferInfo info) {
        if (decoder == null) return;
        buffers = new ByteBuffer[NUMBER_OF_BUFFERS];
        int frameIndex = 0;
        for (int i = 0; i < NUMBER_OF_BUFFERS; i++) {
            buffers[i] = ByteBuffer.allocateDirect(width * height * 3);
            buffers[i].order(ByteOrder.nativeOrder());
            buffers[i].position(0);
        }
        long startMs = System.currentTimeMillis();
        for (; ; ) {
            if (decoder == null) return;
            if(!end_of_input_file) {
                int inputBufferIndex = decoder.dequeueInputBuffer(0);
                if (inputBufferIndex >= 0) {
                    int size = extractor.readSampleData(decoder.getInputBuffer(inputBufferIndex), 0);
                    if (size < 0) {
                        // End Of File
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        end_of_input_file = true;
                        Log.d("VideoDecode", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                    } else {
                        decoder.queueInputBuffer(inputBufferIndex, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            outputBufferIndex = decoder.dequeueOutputBuffer(info, 0);
            if (outputBufferIndex >= 0) {
                if (info.flags != 0) {
                    Log.d("VideoDecode", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                    decoder.stop();
                    decoder.release();
                    extractor.release();
                    decoder = null;
                    return;
                }
                ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufferIndex);
                Image outputImage = decoder.getOutputImage(outputBufferIndex);
                if(outputImage == null){
                    Log.d("VideoDecode", "OutputBuffer null");
                    decoder.stop();
                    decoder.release();
                    extractor.release();
                    decoder = null;
                    return;
                }

                byte[] byteData;
                ByteBuffer yBuffer = outputImage.getPlanes()[0].getBuffer();
                ByteBuffer uBuffer = outputImage.getPlanes()[1].getBuffer();
                ByteBuffer vBuffer = outputImage.getPlanes()[2].getBuffer();

                int ySize = yBuffer.remaining();
                int uSize = uBuffer.remaining();
                int vSize = vBuffer.remaining();

                byteData = new byte[ySize + uSize + vSize];

                //U and V are swapped
                yBuffer.get(byteData, 0, ySize);
                vBuffer.get(byteData, ySize, vSize);
                uBuffer.get(byteData, ySize + vSize, uSize);

                buffers[currentBuffer].put(byteData);
                buffers[currentBuffer].position(0);

                //SystemClock.sleep(100);

                // Portrait videos recorder with the default Android camera will have a rotation of 270 degrees
                // If you are using prepared videos with expected rotation, change orientation to 0
                imageReceiver.receiveFrame(buffers[currentBuffer], outputImage.getWidth(), outputImage.getHeight(),270, false, DeepARImageFormat.YUV_420_888, outputImage.getPlanes()[1].getPixelStride());

                decoder.releaseOutputBuffer(outputBufferIndex, false);
                currentBuffer = ( currentBuffer + 1 ) % NUMBER_OF_BUFFERS;
                outputImage.close();



            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d("VideoDecode", "INFO_OUTPUT_BUFFERS_CHANGED");
            }
            else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.d("VideoDecode", "INFO_TRY_AGAIN_LATER!");
            }
        }
    }
}