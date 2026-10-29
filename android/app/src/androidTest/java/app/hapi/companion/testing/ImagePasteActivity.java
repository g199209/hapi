package app.hapi.companion.testing;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Separate test-APK UID; framework-only so it also runs beside a signed R8 app. */
public class ImagePasteActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView result = new TextView(this);
        result.setContentDescription("HAPI_IMAGE_PASTE_RESULT");
        result.setTextSize(16);
        Button paste = new Button(this);
        paste.setText("Paste image");
        paste.setOnClickListener(view -> {
            try {
                ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
                Uri uri = clip.getItemAt(0).getUri();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                byte[] bytes = output.toByteArray();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                StringBuilder hash = new StringBuilder();
                for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                    hash.append(Integer.toHexString((part & 255) | 256).substring(1));
                }
                result.setText(new JSONObject().put("uid", Process.myUid())
                    .put("mime", getContentResolver().getType(uri))
                    .put("imageMime", clip.getDescription().hasMimeType("image/*"))
                    .put("bytes", bytes.length).put("width", bounds.outWidth).put("height", bounds.outHeight)
                    .put("sha256", hash.toString()).put("uri", uri.toString()).toString());
            } catch (Exception error) {
                result.setText("ERROR: " + error);
            }
        });
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 96, 32, 32);
        layout.addView(paste);
        Button done = new Button(this);
        done.setText("Return to HAPI");
        done.setOnClickListener(view -> finish());
        layout.addView(done);
        layout.addView(result);
        setContentView(layout);
    }
}
