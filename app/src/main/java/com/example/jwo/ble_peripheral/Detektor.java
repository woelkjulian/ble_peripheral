package com.example.jwo.ble_peripheral;

import android.content.Intent;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.util.Log;

/**
 * Created by jwo on 02.07.17.
 */

class AufnahmeTask extends AsyncTask<Long, Verarbeitungsergebnis, Void> {
    private MainActivity activity;
    private AudioRecord recorder;
    private final int sampleRateInHz = 44100;
    private final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private int minPufferGroesseInBytes;
    private int pufferGroesseInBytes;
    private RingPuffer ringPuffer = new RingPuffer(10);
    public final static String ACTION_ALARM_TRIGGERED =
            "com.example.bluetooth.le.ACTION_ALARM_TRIGGERED";

    public AufnahmeTask(MainActivity activity) {
        this.activity = activity;
        minPufferGroesseInBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        pufferGroesseInBytes = minPufferGroesseInBytes * 2;
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRateInHz, channelConfig, audioFormat, pufferGroesseInBytes);

        int anzahlBytesProAbtastwert;
        String s;
        switch (recorder.getAudioFormat()) {
            case AudioFormat.ENCODING_PCM_8BIT:
                s = "8 Bit PCM ";
                anzahlBytesProAbtastwert = 1;
                break;
            case AudioFormat.ENCODING_PCM_16BIT:
                s = "16 Bit PCM";
                anzahlBytesProAbtastwert = 2;
                break;
            case AudioFormat.ENCODING_PCM_FLOAT:
                s = "Float PCM";
                anzahlBytesProAbtastwert = 4;
                break;
            default:
                throw new IllegalArgumentException();
        }

        switch (recorder.getChannelConfiguration()) {
            case AudioFormat.CHANNEL_IN_MONO:
                s = "Mono";
                break;
            case AudioFormat.CHANNEL_IN_STEREO:
                s = "Stereo";
                anzahlBytesProAbtastwert *= 2;
                break;
            case AudioFormat.CHANNEL_INVALID:
                s = "ungültig";
                break;
            default:
                throw new IllegalArgumentException();
        }

        int pufferGroesseInAnzahlAbtastwerten = pufferGroesseInBytes / anzahlBytesProAbtastwert;
        int pufferGroesseInMillisekunden = 1000 * pufferGroesseInAnzahlAbtastwerten / recorder.getSampleRate();
    }

    @Override
    protected Void doInBackground(Long... params) {
        recorder.startRecording();
        short[] puffer = new short[pufferGroesseInBytes / 2];
        long lastTime = System.currentTimeMillis();
        float verarbeitungsrate = 0;
        float hintergrundRauschen = 0;
        final int maxZaehlerZeitMessung = 10;
        int zaehlerZeitMessung = 0;
        int anzahlVerarbeitet = 0;
        GleitenderMittelwert gleitenderMittelwert = new GleitenderMittelwert(0.3f);
        GleitenderMittelwert rauschenMittelwert = new GleitenderMittelwert(0.3f);

        for (; ; ) {
            if (isCancelled()) {
                break;
            } else {
                int n = recorder.read(puffer, 0, puffer.length);
                Verarbeitungsergebnis ergebnis = verarbeiten(puffer, n);
                anzahlVerarbeitet += n;

                zaehlerZeitMessung++;
                if (zaehlerZeitMessung == maxZaehlerZeitMessung) {
                    long time = System.currentTimeMillis();
                    long deltaTime = time - lastTime;
                    verarbeitungsrate = 1000.0f * anzahlVerarbeitet / deltaTime;
                    verarbeitungsrate = gleitenderMittelwert.mittel(verarbeitungsrate);
                    hintergrundRauschen = ergebnis.mittelwert;
                    hintergrundRauschen = rauschenMittelwert.mittel(hintergrundRauschen);

                    zaehlerZeitMessung = 0;
                    anzahlVerarbeitet = 0;
                    lastTime = time;
                }

                ergebnis.verarbeitungsrate = (int) verarbeitungsrate;
                ergebnis.mittelwert = (float) hintergrundRauschen;
                publishProgress(ergebnis);

                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Log.i("AufnahmeTask: ", e.toString());
                }
            }
        }
        recorder.release();
        return null;
    }

    private Verarbeitungsergebnis verarbeiten(short[] daten, int n) {
        String status;
        short maxAmp = -1;
        float mittelwert = 0;
        if (n == AudioRecord.ERROR_INVALID_OPERATION) {
            status = "ERROR_INVALID_OPERATION";
        } else if (n == AudioRecord.ERROR_BAD_VALUE) {
            status = "ERROR_BAD_VALUE";
        } else {
            status = "OK";
            short max = 0;
            for (int i = 0; i < n; i++) {
                if (daten[i] > max) {
                    max = daten[i];
                }
            }

            ringPuffer.hinzufuegen(max);
            maxAmp = ringPuffer.maximum();
            mittelwert = ringPuffer.mittelwert();
        }

        return new Verarbeitungsergebnis(status, maxAmp, 0, mittelwert);
    }

    @Override
    protected void onProgressUpdate(Verarbeitungsergebnis... progress) {
        super.onProgressUpdate(progress);
        boolean triggerAlarm = false;

        float rauschen = progress[0].mittelwert;
        float schwelle = 30000;
        int max = progress[0].maxAmp;


        if (max >= schwelle ) {
            triggerAlarm = true;
        } else {
            triggerAlarm = false;
        }

        if (triggerAlarm) {
            activity.setAlarm(true);
        } else {
            activity.setAlarm(false);
        }
    }

}

class Verarbeitungsergebnis {
    String status;
    short maxAmp;
    int verarbeitungsrate;
    float mittelwert;

    Verarbeitungsergebnis(String status, short maxAmp, int verarbeitungsrate, float mittelwert) {
        this.status = status;
        this.maxAmp = maxAmp;
        this.verarbeitungsrate = verarbeitungsrate;
        this.mittelwert = mittelwert;
    }
}

class RingPuffer {
    private short[] puffer;
    private final int laenge;
    private int anzahlEnthaltenerDaten;
    private int position;

    public RingPuffer(int n) {
        laenge = n;
        anzahlEnthaltenerDaten = 0;
        position = 0;
        puffer = new short[laenge];
    }

    public void hinzufuegen(short wert) {
        puffer[position] = wert;
        position++;
        if (position >= laenge) {
            position = 0;
        }
        if (anzahlEnthaltenerDaten < laenge) {
            anzahlEnthaltenerDaten++;
        }
    }

    public void hinzufuegen(short[] daten) {
        for (short d : daten) {
            puffer[position] = d;
            position++;
            if (position >= laenge) {
                position = 0;
            }
        }
        if (anzahlEnthaltenerDaten < laenge) {
            anzahlEnthaltenerDaten += daten.length;
            if (anzahlEnthaltenerDaten >= laenge) {
                anzahlEnthaltenerDaten = laenge;
            }
        }
    }

    public short maximum() {
        short max = 0;
        for (int i = 0; i < anzahlEnthaltenerDaten; i++) {
            if (puffer[i] > max) {
                max = puffer[i];
            }
        }
        return max;
    }

    public float mittelwert() {
        float summe = 0;
        for (int i = 0; i < anzahlEnthaltenerDaten; i++) {
            summe += puffer[i];
        }
        return summe / anzahlEnthaltenerDaten;
    }
}

class GleitenderMittelwert {
    private final float wichtungNeuerWert;
    private final float wichtungAlterWert;
    private float mittelwert = 0;
    private boolean istMittelwertGesetzt = false;

    GleitenderMittelwert(float wichtungNeuerWert) {
        this.wichtungNeuerWert = wichtungNeuerWert;
        this.wichtungAlterWert = 1 - this.wichtungNeuerWert;
    }

    float mittel(float wert) {
        if (istMittelwertGesetzt) {
            mittelwert = wert * wichtungNeuerWert + mittelwert * wichtungAlterWert;
        } else {
            mittelwert = wert;
            istMittelwertGesetzt = true;
        }
        return mittelwert;
    }
}

