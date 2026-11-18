package com.example.lighttrustvalidation;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

public final class VectorInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        String[][] cases = {
            {"valid", "accepted:42"}, {"foreign-key", "invalid-signature"},
            {"no-domain-separator", "invalid-signature"}, {"whitespace-edit", "invalid-signature"},
            {"edited-payload", "invalid-signature"}, {"newer-schema", "unsupported-schema"},
            {"utf8-bom", "invalid-json"}, {"utf16-le", "invalid-json"}, {"utf16-be", "invalid-json"},
            {"utf32-le", "invalid-json"}, {"utf32-be", "invalid-json"}
        };
        int failures = 0;
        for (int i = 0; i < cases.length; i++) {
            Bundle status = new Bundle();
            status.putString("class", "MinifiedBundleVectors"); status.putString("test", cases[i][0]);
            status.putInt("numtests", cases.length); status.putInt("current", i + 1);
            sendStatus(1, status);
            try {
                String actual = VectorHarness.verify(getTargetContext(), cases[i][0]);
                if (!actual.equals(cases[i][1])) throw new AssertionError("Expected " + cases[i][1] + ", got " + actual);
                status.putString("stream", "."); sendStatus(0, status);
            } catch (Throwable error) {
                failures++;
                status.putString("stack", android.util.Log.getStackTraceString(error));
                status.putString("stream", cases[i][0] + " failed: " + error + "\n");
                sendStatus(-2, status);
            }
        }
        Bundle result = new Bundle();
        result.putString("stream", "\nTests run: " + cases.length + ", Failures: " + failures + "\n");
        result.putInt("vectorFailures", failures);
        finish(Activity.RESULT_OK, result);
    }
}
