package ai.deepar.video_processing_example;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final String OUTPUT_NAME = "output.mp4";

    private final int RESULT_LOAD_VIDEO = 123;

    private Handler handler;

    private List<Effect> effects;
    private int selectedEffectIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        } else {
            // Permission has already been granted
            initialize();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 1 && grantResults.length > 0) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    return; // no permission
                }
                initialize();
            }
        }
    }

    private void initialize() {
        setContentView(R.layout.activity_main);
        initializeHandler();
        initializeEffects();
        initializeViews();
    }

    private void initializeEffects() {
        effects = new ArrayList<>();
        effects.add(new Effect("Aviators", "aviators", "aviators_toothpick.png"));
        effects.add(new Effect("Big mouth", "bigmouth", "become_a_big_mouth.png"));
        effects.add(new Effect("Dalmatian", "dalmatian", "dalmatian_v2.png"));
        effects.add(new Effect("Flowers", "flowers", "flowers.png"));
        effects.add(new Effect("Koala", "koala", "koala.png"));
        effects.add(new Effect("Lion", "lion", "lion.png"));
        effects.add(new Effect("Mud mask", "mudmask", "mudmask.png"));
        effects.add(new Effect("Pug", "pug", "pug_v2.png"));
        effects.add(new Effect("Sleeping mask", "sleepingmask", "sleepingmask.png"));
        effects.add(new Effect("Small face", "smallface", "smallface.png"));
        effects.add(new Effect("Triple face", "tripleface", "tripleface.png"));
        effects.add(new Effect("Twisted face", "twistedface", "twistedface.png"));
    }

    private void initializeViews() {
        GridView gridView = findViewById(R.id.gridView);
        gridView.setAdapter(new ArrayAdapter<Effect>(this, R.layout.effect_thumbnail, effects) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View resultView = convertView;
                if (resultView == null) {
                    resultView = LayoutInflater.from(MainActivity.this).inflate(R.layout.effect_thumbnail, null);
                }
                Effect item = getItem(position);
                if (item != null) {
                    ImageView imageView = resultView.findViewById(R.id.imageView);
                    resultView.setBackground(selectedEffectIndex == position ? getDrawable(R.drawable.effect_thumbnail_border) : null);
                    imageView.setOnClickListener(view -> {
                        selectedEffectIndex = position;
                        notifyDataSetChanged();
                        Log.d(TAG, "Picked '" + effects.get(selectedEffectIndex).getName() + "' effect");
                    });
                    try (InputStream is = getAssets().open(item.getThumbnailPath())) {
                        Bitmap bitmap = BitmapFactory.decodeStream(is);
                        if (bitmap != null) {
                            imageView.setImageBitmap(bitmap);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                return resultView;
            }
        });

        Button pickVideoButton = findViewById(R.id.pickVideoButton);
        pickVideoButton.setOnClickListener(view -> {
            Intent videoPickerIntent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            videoPickerIntent.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
            startActivityForResult(Intent.createChooser(videoPickerIntent, "Select Video"), RESULT_LOAD_VIDEO);
        });
    }

    private void initializeHandler() {
        HandlerThread handlerThread = new HandlerThread("video-processing");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    public String getPath(Uri uri) {
        String[] projection = new String[]{MediaStore.Video.Media.DATA};
        Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
            cursor.moveToFirst();
            String path = cursor.getString(columnIndex);
            cursor.close();
            return path;
        } else {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int reqCode, int resultCode, Intent data) {
        super.onActivityResult(reqCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            Log.d(TAG, "onActivityResult: No path has been picked.");
            return;
        }
        Uri videoUri = data.getData();
        String path = getPath(videoUri);
        if (path == null) {
            Log.d(TAG, "Could not resolve video path.");
            return;
        }

        findViewById(R.id.progressLayout).setVisibility(View.VISIBLE);
        findViewById(R.id.pickVideoButton).setEnabled(false);

        File outputFile = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), OUTPUT_NAME);
        Log.d(TAG, "onActivityResult: processing video...");
        new VideoProcessor(this, handler, (progress) -> {
            @SuppressLint("DefaultLocale") String text = String.format("%.1f%%", progress);
            ((TextView) findViewById(R.id.progressText)).setText(text);
        }, () -> {
            findViewById(R.id.progressLayout).setVisibility(View.INVISIBLE);
            findViewById(R.id.pickVideoButton).setEnabled(true);

            Log.d(TAG, "Video saved: " + outputFile.getAbsolutePath());
            Toast.makeText(MainActivity.this, "Video saved: " + outputFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }).processVideo(videoUri, outputFile, effects.get(selectedEffectIndex).getPath());
    }
}
