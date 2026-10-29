package tech.thessemaj.zygiskrewire;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Minimal launcher activity: makes the payload-carrier app visible and
 * launchable from Android settings. Displays the module id read from
 * {@code assets/payload/identity.props}; nothing else.
 */
public class CarrierActivity extends Activity {

    private static final String PROPS_PATH = "payload/identity.props";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setPadding(48, 48, 48, 48);
        view.setText("payload id: " + readPayloadId());
        setContentView(view);
    }

    private String readPayloadId() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getAssets().open(PROPS_PATH)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0 && "id".equals(line.substring(0, eq).trim())) {
                    String value = line.substring(eq + 1).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to the neutral error text.
        }
        return "(missing)";
    }
}
