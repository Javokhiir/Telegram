package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UMessageConfig;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UMessageFeaturePreview;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** One U message switch on its own page: a live preview, the switch and the full explanation. */
public class UMessageFeatureActivity extends BaseFragment {

    private static final int SWITCH = 1;

    /** Where a switch keeps its value; {@link #key} also picks the preview scenario. */
    public abstract static class Switch {
        final String key;
        final int titleRes;
        final int infoRes;

        Switch(String key, int titleRes, int infoRes) {
            this.key = key;
            this.titleRes = titleRes;
            this.infoRes = infoRes;
        }

        public abstract boolean get();

        public abstract void toggle(BaseFragment fragment, Runnable done);
    }

    public static final Switch BLOCK_ADS = new Switch(UMessageConfig.KEY_BLOCK_ADS, R.string.UMessageBlockAds, R.string.UMessageBlockAdsInfo) {
        @Override
        public boolean get() {
            return UMessageConfig.isAdsBlocked();
        }

        @Override
        public void toggle(BaseFragment fragment, Runnable done) {
            UMessageConfig.setAdsBlocked(!get());
            done.run();
        }
    };

    public static final Switch USE_PROXY = new Switch(UMessageConfig.KEY_PROXY_FALLBACK, R.string.UMessageUseProxy, R.string.UMessageUseProxyInfo) {
        @Override
        public boolean get() {
            return UMessageConfig.isProxyFallbackEnabled();
        }

        @Override
        public void toggle(BaseFragment fragment, Runnable done) {
            UMessageConfig.setProxyFallbackEnabled(!get());
            done.run();
        }
    };

    /** A Privacy Guard switch from {@link PrivacyGuardSettings}. */
    public static Switch privacyGuard(String key, int titleRes, int infoRes) {
        return new Switch(key, titleRes, infoRes) {
            @Override
            public boolean get() {
                return PrivacyGuardSettings.get(key);
            }

            @Override
            public void toggle(BaseFragment fragment, Runnable done) {
                PrivacyGuardSettings.set(key, !get());
                done.run();
            }
        };
    }

    private final Switch sw;
    private final String key;
    private final int titleRes;
    private final int infoRes;

    private UniversalRecyclerView listView;
    private UMessageFeaturePreview preview;

    public UMessageFeatureActivity(String key, int titleRes, int infoRes) {
        this(new Switch(key, titleRes, infoRes) {
            @Override
            public boolean get() {
                return UMessageConfig.get(key);
            }

            @Override
            public void toggle(BaseFragment fragment, Runnable done) {
                UMessageSettingsActivity.toggle(fragment, key, done);
            }
        });
    }

    public UMessageFeatureActivity(Switch sw) {
        this.sw = sw;
        this.key = sw.key;
        this.titleRes = sw.titleRes;
        this.infoRes = sw.infoRes;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(titleRes));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        preview = new UMessageFeaturePreview(context, currentAccount, key, getResourceProvider());
        preview.setFeatureEnabled(sw.get());

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = contentView;
        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCustom(preview));
        items.add(UItem.asShadow(null));
        items.add(UItem.asCheck(SWITCH, LocaleController.getString(titleRes)).setChecked(sw.get()));
        items.add(UItem.asShadow(LocaleController.getString(infoRes)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == SWITCH) {
            sw.toggle(this, this::update);
        }
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
        if (preview != null) {
            preview.setFeatureEnabled(sw.get());
        }
    }
}
