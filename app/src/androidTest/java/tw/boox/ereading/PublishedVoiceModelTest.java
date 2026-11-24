package tw.boox.ereading;

import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.vosk.Model;
import org.vosk.Recognizer;

@RunWith(AndroidJUnit4.class)
public class PublishedVoiceModelTest {
    @Test public void mirroredModelInstallsAndInitializesVosk() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var app = instrumentation.getTargetContext();
        try {
            try (var zip = instrumentation.getContext().getAssets().open("published-voice-model.zip")) {
                VoiceModel.install(app, zip);
            }
            assertTrue(VoiceModel.ready(app));
            try (var model = new Model(VoiceModel.folder(app).getAbsolutePath());
                 var recognizer = new Recognizer(model, 16000f)) {
                recognizer.acceptWaveForm(new byte[3200], 3200);
                assertTrue(recognizer.getFinalResult().contains("text"));
            }
        } finally {
            VoiceModel.delete(VoiceModel.folder(app));
        }
    }
}
