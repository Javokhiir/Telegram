package org.telegram.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.privacyguard.OwnerFaceStore;
import org.telegram.messenger.privacyguard.PrivacyGuardController;
import org.telegram.messenger.privacyguard.PrivacyGuardSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** Settings -> Privacy -> Privacy Guard. */
public class PrivacyGuardSettingsActivity extends BaseFragment {

    private static final int REQUEST_CAMERA = 3772;

    private static final int ENABLED = 1;
    private static final int REGISTER = 2;
    private static final int DELETE = 3;
    private static final int PASSCODE = 4;
    private static final int PASSCODE_REMOVE = 5;
    private static final int MODE_LOOKING = 10;
    private static final int MODE_ANY_FACE = 11;
    private static final int ACTION_BLUR = 20;
    private static final int ACTION_LOCK = 21;
    private static final int OWNER_AWAY = 30;
    private static final int PROTECT_MEDIA = 31;
    private static final int PROTECT_COMPOSER = 32;
    private static final int PROTECT_SCREENSHOTS = 33;

    private UniversalRecyclerView listView;
    /** What to do once the camera permission is granted. */
    private Runnable afterPermission;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.PrivacyGuard));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = contentView;
        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean supported = PrivacyGuardSettings.isSupported();
        final boolean enrolled = OwnerFaceStore.hasOwner();
        final boolean enabled = PrivacyGuardSettings.isEnabled() && enrolled;

        items.add(UItem.asCheck(ENABLED, LocaleController.getString(R.string.PrivacyGuard)).setChecked(enabled).setEnabled(supported));
        items.add(UItem.asShadow(LocaleController.getString(supported ? R.string.PrivacyGuardAbout : R.string.PrivacyGuardUnsupported)));
        if (!supported) {
            return;
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyGuardRegisteredFace)));
        items.add(UItem.asButton(REGISTER, R.drawable.msg2_permissions,
                LocaleController.getString(enrolled ? R.string.PrivacyGuardReregisterFace : R.string.PrivacyGuardRegisterFace),
                LocaleController.getString(enrolled ? R.string.PrivacyGuardFaceConfigured : R.string.PrivacyGuardFaceNotConfigured)));
        if (enrolled) {
            items.add(UItem.asButton(DELETE, R.drawable.msg_delete, LocaleController.getString(R.string.PrivacyGuardDeleteFace)).red());
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.PrivacyGuardFaceInfo)));

        final boolean hasPasscode = PrivacyGuardSettings.hasGuardPasscode();
        items.add(UItem.asButton(PASSCODE, R.drawable.msg_pin_code,
                LocaleController.getString(R.string.PrivacyGuardSetPasscode),
                LocaleController.getString(hasPasscode ? R.string.PrivacyGuardPasscodeSet : R.string.PrivacyGuardPasscodeHint)));
        if (hasPasscode) {
            items.add(UItem.asButton(PASSCODE_REMOVE, R.drawable.msg_delete, LocaleController.getString(R.string.PrivacyGuardRemovePasscode)).red());
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.PrivacyGuardSetPasscodeInfo)));

        final int mode = PrivacyGuardSettings.getMode();
        items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyGuardMode)));
        items.add(UItem.asRadio(MODE_LOOKING, LocaleController.getString(R.string.PrivacyGuardModeLooking)).setChecked(mode == PrivacyGuardSettings.MODE_LOOKING));
        items.add(UItem.asRadio(MODE_ANY_FACE, LocaleController.getString(R.string.PrivacyGuardModeAnyFace)).setChecked(mode == PrivacyGuardSettings.MODE_ANY_FACE));
        items.add(UItem.asShadow(null));

        final int action = PrivacyGuardSettings.getAction();
        items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyGuardAction)));
        items.add(UItem.asRadio(ACTION_BLUR, LocaleController.getString(R.string.PrivacyGuardActionBlur)).setChecked(action == PrivacyGuardSettings.ACTION_BLUR));
        items.add(UItem.asRadio(ACTION_LOCK, LocaleController.getString(R.string.PrivacyGuardActionLock)).setChecked(action == PrivacyGuardSettings.ACTION_LOCK));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyGuardSensitivity)));
        items.add(UItem.asSlideView(new String[]{
                LocaleController.getString(R.string.PrivacyGuardSensitivityLow),
                LocaleController.getString(R.string.PrivacyGuardSensitivityBalanced),
                LocaleController.getString(R.string.PrivacyGuardSensitivityHigh)
        }, PrivacyGuardSettings.getSensitivity(), PrivacyGuardSettings::setSensitivity));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PrivacyGuardSensitivityInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.PrivacyGuardAdditional)));
        items.add(UItem.asButtonCheck(OWNER_AWAY, LocaleController.getString(R.string.PrivacyGuardHideOwnerAway), LocaleController.getString(R.string.PrivacyGuardHideOwnerAwayInfo))
                .setChecked(PrivacyGuardSettings.isHideWhenOwnerAway()));
        items.add(UItem.asButtonCheck(PROTECT_MEDIA, LocaleController.getString(R.string.PrivacyGuardProtectMedia), LocaleController.getString(R.string.PrivacyGuardProtectMediaInfo))
                .setChecked(PrivacyGuardSettings.isProtectMedia()));
        items.add(UItem.asButtonCheck(PROTECT_COMPOSER, LocaleController.getString(R.string.PrivacyGuardProtectComposer), LocaleController.getString(R.string.PrivacyGuardProtectComposerInfo))
                .setChecked(PrivacyGuardSettings.isProtectComposer()));
        items.add(UItem.asButtonCheck(PROTECT_SCREENSHOTS, LocaleController.getString(R.string.PrivacyGuardProtectScreenshots), LocaleController.getString(R.string.PrivacyGuardProtectScreenshotsInfo))
                .setChecked(PrivacyGuardSettings.isProtectScreenshots()));
        items.add(UItem.asShadow(null));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ENABLED:
                if (!PrivacyGuardSettings.isSupported()) {
                    return;
                }
                if (PrivacyGuardSettings.isEnabled() && OwnerFaceStore.hasOwner()) {
                    PrivacyGuardSettings.setEnabled(false);
                    update();
                } else {
                    withCameraPermission(() -> {
                        if (OwnerFaceStore.hasOwner()) {
                            PrivacyGuardSettings.setEnabled(true);
                            update();
                        } else {
                            presentFragment(new PrivacyGuardEnrollActivity(true));
                        }
                    });
                }
                break;
            case REGISTER:
                withCameraPermission(() -> presentFragment(new PrivacyGuardEnrollActivity(!OwnerFaceStore.hasOwner())));
                break;
            case DELETE:
                confirmDelete();
                break;
            case PASSCODE:
                showPasscodeSetup();
                break;
            case PASSCODE_REMOVE:
                PrivacyGuardSettings.clearGuardPasscode();
                update();
                break;
            case MODE_LOOKING:
                PrivacyGuardSettings.setMode(PrivacyGuardSettings.MODE_LOOKING);
                update();
                break;
            case MODE_ANY_FACE:
                PrivacyGuardSettings.setMode(PrivacyGuardSettings.MODE_ANY_FACE);
                update();
                break;
            case ACTION_BLUR:
                PrivacyGuardSettings.setAction(PrivacyGuardSettings.ACTION_BLUR);
                update();
                break;
            case ACTION_LOCK:
                PrivacyGuardSettings.setAction(PrivacyGuardSettings.ACTION_LOCK);
                update();
                break;
            case OWNER_AWAY:
                openOrToggle(view, x, PrivacyGuardSettings.KEY_OWNER_AWAY, R.string.PrivacyGuardHideOwnerAway, R.string.PrivacyGuardHideOwnerAwayInfo);
                break;
            case PROTECT_MEDIA:
                openOrToggle(view, x, PrivacyGuardSettings.KEY_PROTECT_MEDIA, R.string.PrivacyGuardProtectMedia, R.string.PrivacyGuardProtectMediaInfo);
                break;
            case PROTECT_COMPOSER:
                openOrToggle(view, x, PrivacyGuardSettings.KEY_PROTECT_COMPOSER, R.string.PrivacyGuardProtectComposer, R.string.PrivacyGuardProtectComposerInfo);
                break;
            case PROTECT_SCREENSHOTS:
                openOrToggle(view, x, PrivacyGuardSettings.KEY_PROTECT_SCREENSHOTS, R.string.PrivacyGuardProtectScreenshots, R.string.PrivacyGuardProtectScreenshotsInfo);
                break;
        }
    }

    private void openOrToggle(View view, float x, String key, int titleRes, int infoRes) {
        if (UMessageSettingsActivity.isSwitchClick(view, x)) {
            toggle(key);
        } else {
            presentFragment(new UMessageFeatureActivity(UMessageFeatureActivity.privacyGuard(key, titleRes, infoRes)));
        }
    }

    private void toggle(String key) {
        PrivacyGuardSettings.set(key, !PrivacyGuardSettings.get(key));
        update();
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.PrivacyGuardDeleteFace));
        builder.setMessage(LocaleController.getString(R.string.PrivacyGuardDeleteFaceConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            PrivacyGuardSettings.setEnabled(false);
            OwnerFaceStore.delete();
            update();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.redPositive();
    }

    private void showPasscodeSetup() {
        if (!(fragmentView instanceof FrameLayout)) {
            return;
        }
        final FrameLayout root = (FrameLayout) fragmentView;
        final org.telegram.ui.Components.PrivacyGuardUnlockView[] view = new org.telegram.ui.Components.PrivacyGuardUnlockView[1];
        view[0] = new org.telegram.ui.Components.PrivacyGuardUnlockView(getParentActivity(), getResourceProvider(),
                org.telegram.ui.Components.PrivacyGuardUnlockView.MODE_CREATE, false,
                new org.telegram.ui.Components.PrivacyGuardUnlockView.Callback() {
            @Override
            public void onUnlocked() {
                if (view[0] != null) root.removeView(view[0]);
                update();
            }

            @Override
            public void onCancel() {
                if (view[0] != null) root.removeView(view[0]);
            }

            @Override
            public void onBiometric() {
            }
        });
        root.addView(view[0], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        view[0].bringToFront();
        view[0].requestFocus();
    }

    private void withCameraPermission(Runnable action) {
        if (PrivacyGuardController.hasCameraPermission()) {
            action.run();
            return;
        }
        final Activity activity = getParentActivity();
        if (activity == null || Build.VERSION.SDK_INT < 23) {
            return;
        }
        afterPermission = action;
        activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CAMERA) {
            return;
        }
        final Runnable action = afterPermission;
        afterPermission = null;
        if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (action != null) {
                action.run();
            }
        } else if (getParentActivity() != null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            builder.setTitle(LocaleController.getString(R.string.PrivacyGuard));
            builder.setMessage(LocaleController.getString(R.string.PrivacyGuardCameraPermission));
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(builder.create());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }
}
