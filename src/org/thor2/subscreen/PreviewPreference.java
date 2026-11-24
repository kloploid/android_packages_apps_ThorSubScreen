package org.thor2.subscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/** Живое превью внешнего экрана; нажатие листает страницы. */
public class PreviewPreference extends Preference {
    private static final Renderer.Page[] PAGES = {
            Renderer.Page.CLOCK, Renderer.Page.NOTIFICATIONS, Renderer.Page.MUSIC, Renderer.Page.PET,
            Renderer.Page.EIGHTBALL, Renderer.Page.CHARGING, Renderer.Page.CALL};
    private static final int[] NAMES = {
            R.string.page_clock, R.string.page_notifications, R.string.page_music, R.string.page_pet,
            R.string.page_eightball, R.string.page_charging, R.string.page_call};

    private ImageView mImage;
    private TextView mLabel;
    private Renderer mRenderer;
    private int mPage;

    public PreviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preview);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mImage = (ImageView) holder.findViewById(R.id.preview_image);
        mLabel = (TextView) holder.findViewById(R.id.preview_label);
        refresh();
    }

    @Override
    protected void onClick() {
        mPage = (mPage + 1) % PAGES.length;
        refresh();
    }

    void refresh() {
        if (mImage == null) {
            return;
        }
        Context c = getContext();
        if (mRenderer == null) {
            mRenderer = new Renderer(c, new Prefs(c));
        }
        Renderer.State s = SubScreenController.get(c).snapshot();
        s.page = PAGES[mPage];
        s.toast = null;
        // примеры, если реальных данных нет
        if (s.page == Renderer.Page.MUSIC && s.musicTitle == null) {
            s.musicTitle = c.getString(R.string.demo_track);
            s.musicArtist = c.getString(R.string.demo_artist);
            s.musicPlaying = true;
        }
        if (s.page == Renderer.Page.EIGHTBALL) {
            s.eightBall = c.getResources().getStringArray(R.array.eightball_answers)[0];
        }
        if (s.page == Renderer.Page.CALL) {
            s.ringing = true;
            s.caller = c.getString(R.string.demo_caller);
            s.callerNumber = "+7 900 000-00-00";
        }
        if (s.page == Renderer.Page.CHARGING) {
            s.charging = true;
        }
        Bitmap frame = mRenderer.draw(s).copy(Bitmap.Config.ARGB_8888, false);
        mImage.setImageBitmap(frame);
        mLabel.setText(c.getString(R.string.preview_label, c.getString(NAMES[mPage])));
    }
}
