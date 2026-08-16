package io.github.mexus.gcallrecorder;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.lang.reflect.Member;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.vvb2060.callrecording.xposed.DexHelper;

public class Module extends XposedModule {
    private static final String TAG = "CallRec";
    private static final String DIALER = "com.google.android.dialer";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "onModuleLoaded: " + param.getProcessName()
                + " API " + getApiVersion());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!DIALER.equals(param.getPackageName())) return;
        if (!param.isFirstPackage()) return;
        log(Log.INFO, TAG, "onPackageReady: dialer, classloader=" + param.getClassLoader());

        try (DexHelper dex = new DexHelper(param.getClassLoader())) {
            boolean silenced = installSilenceHooks(dex);
            if (!silenced) {
                log(Log.ERROR, TAG, "ABORT: disclosure not silenced -> leaving recording disabled (fail-safe)");
                return;
            }
            boolean enabled = installEnableHooks(dex);
            boolean auto = installAutoRecordHooks(dex);
            log(Log.INFO, TAG, "ready: silenced=" + silenced + " enabled=" + enabled + " auto=" + auto);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook install failed -> recording disabled (fail-safe)", t);
        }
    }

    private void hookConst(Member m, Object value) {
        hook((java.lang.reflect.Method) m).intercept(chain -> value);
    }

    /** find first method whose body references `str`, matching the given return/arg shape */
    private Member findByString(DexHelper dex, String str, long retClass, short paramCount,
                                String shorty, long[] paramClasses) {
        long[] hits = dex.findMethodUsingString(
                str, false, retClass, paramCount, shorty, -1, paramClasses, null, null, true);
        return Arrays.stream(hits).mapToObj(dex::decodeMethodIndex)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private boolean hookPublicTts() {
        try {
            java.lang.reflect.Method synth = TextToSpeech.class.getDeclaredMethod(
                    "synthesizeToFile", CharSequence.class, Bundle.class, File.class, String.class);
            hook(synth).intercept(chain -> {
                Object[] a = { "", chain.getArg(1), chain.getArg(2), chain.getArg(3) };
                Object r;
                try {
                    r = chain.proceed(a);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "proceed() threw, falling back to silent wav", t);
                    r = TextToSpeech.ERROR;
                }
                if (!Objects.equals(r, TextToSpeech.SUCCESS)) {
                    File file = (File) chain.getArg(2);
                    boolean wrote = false;
                    try { SilentAudio.write(file); r = TextToSpeech.SUCCESS; wrote = true; }
                    catch (Exception e) { log(Log.ERROR, TAG, "silent wav write failed", e); }
                    if (wrote) {
                        try {
                            // mUtteranceProgressListener is a framework-internal field and may
                            // not exist on all OEM builds (hence the guarded reflection).
                            java.lang.reflect.Field f = chain.getThisObject().getClass()
                                    .getDeclaredField("mUtteranceProgressListener");
                            f.setAccessible(true);
                            UtteranceProgressListener l = (UtteranceProgressListener) f.get(chain.getThisObject());
                            if (l != null) l.onDone((String) chain.getArg(3));
                        } catch (ReflectiveOperationException e) { log(Log.WARN, TAG, "onDone skip", e); }
                    }
                }
                return r;
            });
            log(Log.INFO, TAG, "hooked public synthesizeToFile");
            return true;
        } catch (NoSuchMethodException e) {
            log(Log.ERROR, TAG, "public synthesizeToFile not found", e);
            return false;
        }
    }

    private boolean installSilenceHooks(DexHelper dex) {
        // PRIMARY: public-TTS empty-text synthesis. Success of THIS hook defines silence-success,
        // because the user confirms it already silences the announcement on this dialer.
        boolean ok = hookPublicTts(); // returns true if the hook installed

        // CONTINGENCY (best-effort, not counted toward ok): only relevant if Step 9 shows
        // audible disclosure. Remove/keep based on the Step-6 finding.
        Member synth = findByString(dex, "synthesizeTextIntoFile", -1, (short) -1, null, null);
        if (synth != null) {
            hook((java.lang.reflect.Method) synth).intercept(chain -> {
                Object r = chain.proceed();
                for (Object arg : chain.getArgs()) {
                    if (arg instanceof File) { try { SilentAudio.write((File) arg); } catch (Exception ignored) {} }
                }
                return r;
            });
            log(Log.INFO, TAG, "hooked internal disclosure synthesis (contingency)");
        }
        Member beep = findByString(dex, "playBeep", -1, (short) -1, null, null);
        if (beep != null) { hook((java.lang.reflect.Method) beep).intercept(chain -> null); log(Log.INFO, TAG, "muted beep (contingency)"); }

        return ok;
    }

    private boolean installEnableHooks(DexHelper dex) {
        boolean ok = true;

        Member canRecordCall = findByString(dex, "canRecordCall", -1, (short) 0, "Z", null);
        if (canRecordCall != null) { hookConst(canRecordCall, true); log(Log.INFO, TAG, "hooked canRecordCall"); }
        else { log(Log.ERROR, TAG, "canRecordCall not found"); ok = false; }

        Member isCountry = findByString(dex, "isCallRecordingCountry", -1, (short) 0, "Z", null);
        if (isCountry != null) { hookConst(isCountry, true); log(Log.INFO, TAG, "hooked isCallRecordingCountry"); }
        else { log(Log.ERROR, TAG, "isCallRecordingCountry not found"); ok = false; }

        long localeId = dex.encodeClassIndex(Locale.class);
        long mapId = dex.encodeClassIndex(Map.class);
        long stringId = dex.encodeClassIndex(String.class);
        Member getLocale = findByString(dex, "getSupportedLocaleFromCountryCode",
                localeId, (short) 2, null, new long[]{mapId, stringId});
        if (getLocale != null) { hookConst(getLocale, Locale.US); log(Log.INFO, TAG, "hooked getSupportedLocaleFromCountryCode"); }
        else { log(Log.ERROR, TAG, "getSupportedLocaleFromCountryCode not found"); ok = false; }

        // renamed from withinCrosbyGeoFence -> withinCallRecordingGeoFence (with a Geofence spelling fallback)
        Member geo = findByString(dex, "withinCallRecordingGeoFence", -1, (short) 0, "Z", null);
        if (geo == null) geo = findByString(dex, "withinCallRecordingGeofence", -1, (short) 0, "Z", null);
        if (geo != null) { hookConst(geo, true); log(Log.INFO, TAG, "hooked geofence"); }
        else { log(Log.WARN, TAG, "geofence method not found"); } // non-fatal, do not flip ok

        return ok;
    }

    private boolean installAutoRecordHooks(DexHelper dex) {
        boolean ok = false;
        Member canAuto = findByString(dex, "canRecordAutomatically", -1, (short) 0, "Z", null);
        if (canAuto != null) { hookConst(canAuto, true); log(Log.INFO, TAG, "hooked canRecordAutomatically"); ok = true; }
        else { log(Log.ERROR, TAG, "canRecordAutomatically not found"); }

        // outer contact-list gate — force true so unknown numbers also auto-record
        Member inList = findByString(dex, "produceIsCallInAutoRecordContactsList", -1, (short) -1, "Z", null);
        if (inList != null) { hookConst(inList, true); log(Log.INFO, TAG, "hooked produceIsCallInAutoRecordContactsList"); }
        else { log(Log.WARN, TAG, "auto-record contacts-list gate not found (may be unnecessary)"); }

        return ok;
    }
}
