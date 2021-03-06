/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.actfm;

import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONException;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.ActionBar;
import android.support.v4.app.FragmentActivity;
import android.support.v4.view.Menu;
import android.support.v4.view.MenuItem;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.timsu.astrid.R;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.service.ExceptionService;
import com.todoroo.andlib.utility.AndroidUtilities;
import com.todoroo.andlib.utility.DialogUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.actfm.ActFmCameraModule.CameraResultCallback;
import com.todoroo.astrid.actfm.sync.ActFmPreferenceService;
import com.todoroo.astrid.actfm.sync.ActFmSyncService;
import com.todoroo.astrid.activity.FilterListFragment;
import com.todoroo.astrid.activity.ShortcutActivity;
import com.todoroo.astrid.api.Filter;
import com.todoroo.astrid.data.SyncFlags;
import com.todoroo.astrid.data.TagData;
import com.todoroo.astrid.helper.AsyncImageView;
import com.todoroo.astrid.helper.ImageDiskCache;
import com.todoroo.astrid.service.StatisticsConstants;
import com.todoroo.astrid.service.StatisticsService;
import com.todoroo.astrid.service.TagDataService;
import com.todoroo.astrid.service.ThemeService;
import com.todoroo.astrid.tags.TagFilterExposer;
import com.todoroo.astrid.tags.TagService;
import com.todoroo.astrid.ui.PeopleContainer;
import com.todoroo.astrid.ui.PeopleContainer.ParseSharedException;
import com.todoroo.astrid.utility.AstridPreferences;
import com.todoroo.astrid.welcome.HelpInfoPopover;

public class TagSettingsActivity extends FragmentActivity {

    public static final String TOKEN_NEW_FILTER = "newFilter"; //$NON-NLS-1$

    private static final int MENU_SAVE_ID = R.string.TEA_menu_save;
    private static final int MENU_DISCARD_ID = R.string.TEA_menu_discard;

    public static final int REQUEST_ACTFM_LOGIN = 3;

    public static final String TOKEN_AUTOPOPULATE_MEMBERS = "autopopulateMembers"; //$NON-NLS-1$

    public static final String TOKEN_AUTOPOPULATE_NAME = "autopopulateName"; //$NON-NLS-1$

    private static final String MEMBERS_IN_PROGRESS = "members"; //$NON-NLS-1$

    private TagData tagData;
    private Filter filter; // Used for creating shortcuts, only initialized if necessary

    @Autowired TagService tagService;

    @Autowired TagDataService tagDataService;

    @Autowired ActFmSyncService actFmSyncService;

    @Autowired ActFmPreferenceService actFmPreferenceService;

    @Autowired ExceptionService exceptionService;

    private PeopleContainer tagMembers;
    private AsyncImageView picture;
    private EditText tagName;
    private EditText tagDescription;
    private CheckBox isSilent;
    private Bitmap setBitmap;
    private final ImageDiskCache imageCache;

    private boolean isNewTag = false;
    private boolean isDialog;

    public TagSettingsActivity() {
        DependencyInjectionService.getInstance().inject(this);
        imageCache = ImageDiskCache.getInstance();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setupForDialogOrFullscreen();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.tag_settings_activity);
        tagData = getIntent().getParcelableExtra(TagViewFragment.EXTRA_TAG_DATA);
        if (tagData == null) {
            isNewTag = true;
            tagData = new TagData();
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setDisplayShowCustomEnabled(true);
            actionBar.setCustomView(R.layout.header_title_view);
        }

        setUpSettingsPage();

        if(savedInstanceState != null && savedInstanceState.containsKey(MEMBERS_IN_PROGRESS)) {
            final String members = savedInstanceState.getString(MEMBERS_IN_PROGRESS);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    AndroidUtilities.sleepDeep(500);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateMembers(members);
                        }
                    });
                }
            }).start();
        }
        showCollaboratorsPopover();

    }

    private void setupForDialogOrFullscreen() {
        isDialog = AstridPreferences.useTabletLayout(this);
        if (isDialog)
            setTheme(ThemeService.getDialogTheme());
        else {
            ThemeService.applyTheme(this);
            if (ThemeService.getUnsimplifiedTheme() == R.style.Theme_White_Alt)
                getTheme().applyStyle(R.style.SaveAsBackWhite, true);
            else
                getTheme().applyStyle(R.style.SaveAsBack, true);
        }
    }

    private void showCollaboratorsPopover() {
        if (!Preferences.getBoolean(R.string.p_showed_collaborators_help, false)) {
            View members = findViewById(R.id.members_container);
            HelpInfoPopover.showPopover(this, members, R.string.help_popover_collaborators, null);
            Preferences.setBoolean(R.string.p_showed_collaborators_help, true);
        }
    }

    protected void setUpSettingsPage() {
        if (isDialog) {
            findViewById(R.id.save_and_cancel).setVisibility(View.VISIBLE);
            findViewById(R.id.cancel).setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
            findViewById(R.id.save).setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveSettings();
                }
            });
        }

        tagMembers = (PeopleContainer) findViewById(R.id.members_container);
        tagName = (EditText) findViewById(R.id.tag_name);
        tagDescription = (EditText) findViewById(R.id.tag_description);
        picture = (AsyncImageView) findViewById(R.id.picture);
        isSilent = (CheckBox) findViewById(R.id.tag_silenced);
        isSilent.setChecked(tagData.getFlag(TagData.FLAGS, TagData.FLAG_SILENT));

        Button leaveListButton = (Button) findViewById(R.id.leave_list);
        leaveListButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                showDeleteDialog(tagData);
            }
        });
        if (isNewTag) {
            leaveListButton.setVisibility(View.GONE);
        }
        else if (tagData.getValue(TagData.MEMBER_COUNT) > 0) {
            leaveListButton.setText(getString(R.string.tag_leave_button));
        }
        if(actFmPreferenceService.isLoggedIn()) {
            findViewById(R.id.tag_silenced_container).setVisibility(View.VISIBLE);
        }

        picture.setDefaultImageResource(TagService.getDefaultImageIDForTag(tagData.getValue(TagData.NAME)));
        picture.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {
                ActFmCameraModule.showPictureLauncher(TagSettingsActivity.this, null);
            }
        });

        if (isNewTag) {
            findViewById(R.id.create_shortcut_container).setVisibility(View.GONE);
        } else {
            findViewById(R.id.create_shortcut_container).setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (filter == null) {
                        filter = TagFilterExposer.filterFromTagData(TagSettingsActivity.this, tagData);
                    }
                    filter.listingIcon = picture.getImageBitmap();
                    FilterListFragment.showCreateShortcutDialog(TagSettingsActivity.this, ShortcutActivity.createIntent(filter), filter);
                }
            });
        }

        refreshSettingsPage();

        String autopopulateMembers = getIntent().getStringExtra(TOKEN_AUTOPOPULATE_MEMBERS);
        if (!TextUtils.isEmpty(autopopulateMembers)) {
            updateMembers(autopopulateMembers);
            getIntent().removeExtra(TOKEN_AUTOPOPULATE_MEMBERS);
        }

        String autopopulateName = getIntent().getStringExtra(TOKEN_AUTOPOPULATE_NAME);
        if (!TextUtils.isEmpty(autopopulateName)) {
            tagName.setText(autopopulateName);
            getIntent().removeExtra(TOKEN_AUTOPOPULATE_NAME);
        }
    }

    @SuppressWarnings("nls")
    private void saveSettings() {
        String oldName = tagData.getValue(TagData.NAME);
        String newName = tagName.getText().toString().trim();

        if (TextUtils.isEmpty(newName)) {
            return;
        }

        boolean nameChanged = !oldName.equals(newName);
        TagService service = TagService.getInstance();
        if (nameChanged) {
            if (oldName.equalsIgnoreCase(newName)) { // Change the capitalization of a list manually
                tagData.setValue(TagData.NAME, newName);
                service.renameCaseSensitive(oldName, newName);
                tagData.setFlag(TagData.FLAGS, TagData.FLAG_EMERGENT, false);
            } else { // Rename list--check for existing name
                newName = service.getTagWithCase(newName);
                tagName.setText(newName);
                if (!newName.equals(oldName)) {
                    tagData.setValue(TagData.NAME, newName);
                    service.rename(oldName, newName);
                    tagData.setFlag(TagData.FLAGS, TagData.FLAG_EMERGENT, false);
                } else {
                    nameChanged = false;
                }
            }
        }
        //handles description part
        String newDesc = tagDescription.getText().toString();

        tagData.setValue(TagData.TAG_DESCRIPTION, newDesc);

        JSONArray members;
        try {
            members = tagMembers.parseSharedWithAndTags(this, true).optJSONArray("p");
        } catch (JSONException e) {
            exceptionService.displayAndReportError(this, "save-people", e);
            return;
        } catch (ParseSharedException e) {
            if(e.view != null) {
                e.view.setTextColor(Color.RED);
                e.view.requestFocus();
            }
            DialogUtilities.okDialog(this, e.message, null);
            return;
        }
        if (members == null)
            members = new JSONArray();

        if(members.length() > 0 && !actFmPreferenceService.isLoggedIn()) {
            if(newName.length() > 0 && oldName.length() == 0) {
                tagDataService.save(tagData);
            }

            DialogInterface.OnClickListener okListener = new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    startActivityForResult(new Intent(TagSettingsActivity.this, ActFmLoginActivity.class),
                            REQUEST_ACTFM_LOGIN);
                }
            };

            DialogInterface.OnClickListener cancelListener = new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {

                    tagMembers.removeAllViews();
                    tagMembers.addPerson("", ""); //$NON-NLS-1$
                }
            };
            DialogUtilities.okCancelCustomDialog(TagSettingsActivity.this, getString(R.string.actfm_EPA_login_button),
                    getString(R.string.actfm_TVA_login_to_share), R.string.actfm_EPA_login_button,
                    R.string.actfm_EPA_dont_share_button, android.R.drawable.ic_dialog_alert,
                    okListener, cancelListener);

            return;

        }

        int oldMemberCount = tagData.getValue(TagData.MEMBER_COUNT);
        if (members.length() > oldMemberCount) {
            StatisticsService.reportEvent(StatisticsConstants.ACTFM_LIST_SHARED);
        }
        tagData.setValue(TagData.MEMBERS, members.toString());
        tagData.setValue(TagData.MEMBER_COUNT, members.length());
        tagData.setFlag(TagData.FLAGS, TagData.FLAG_SILENT, isSilent.isChecked());

        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(tagName.getWindowToken(), 0);

        if (isNewTag) {
            tagData.putTransitory(SyncFlags.ACTFM_SUPPRESS_SYNC, true);
            tagDataService.save(tagData);

            final Runnable loadTag = new Runnable() {
                @Override
                public void run() {
                    setResult(RESULT_OK, new Intent().putExtra(TOKEN_NEW_FILTER,
                            TagFilterExposer.filterFromTagData(TagSettingsActivity.this, tagData)));
                    finish();
                }
            };

            if(actFmPreferenceService.isLoggedIn()) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        actFmSyncService.pushTagDataOnSave(tagData, tagData.getMergedValues());
                        if(setBitmap != null && tagData.getValue(TagData.REMOTE_ID) > 0)
                            uploadTagPicture(setBitmap);

                        runOnUiThread(loadTag);
                    }
                }).start();
            } else {
                loadTag.run();
            }

            return;
        } else {
            setResult(RESULT_OK);
            tagDataService.save(tagData);
        }

        refreshSettingsPage();
        finish();
    }

    private void saveTagPictureLocally(Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            String tagPicture = ImageDiskCache.getPictureHash(tagData);
            imageCache.put(tagPicture, bitmap);
            tagData.setValue(TagData.PICTURE, tagPicture);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void finish() {
        finishWithAnimation(!isDialog);
    }

    private void finishWithAnimation(boolean backAnimation) {
        super.finish();
        if (backAnimation) {
            AndroidUtilities.callOverridePendingTransition(this, R.anim.slide_right_in, R.anim.slide_right_out);
        }
    }

    private void refreshSettingsPage() {
        tagName.setText(tagData.getValue(TagData.NAME));
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            View customView = ab.getCustomView();
            TextView titleView = (TextView) customView.findViewById(R.id.title);
            if (isNewTag) {
                titleView.setText(getString(R.string.tag_new_list));
            } else {
                titleView.setText(getString(R.string.tag_settings_title));
            }
        } else {
            if (isNewTag) {
                setTitle(getString(R.string.tag_new_list));
            } else {
                setTitle(getString(R.string.tag_settings_title));
            }
        }
        picture.setUrl(tagData.getValue(TagData.PICTURE));
        if (!isNewTag) {
            ImageView shortcut = (ImageView) findViewById(R.id.create_shortcut);
            shortcut.setImageBitmap(FilterListFragment.superImposeListIcon(this, picture.getImageBitmap(), tagData.getValue(TagData.NAME)));
        }

        String peopleJson = tagData.getValue(TagData.MEMBERS);
        updateMembers(peopleJson);

        tagDescription.setText(tagData.getValue(TagData.TAG_DESCRIPTION));

    }

    @SuppressWarnings("nls")
    private void updateMembers(String peopleJson) {
        tagMembers.removeAllViews();
        if(!TextUtils.isEmpty(peopleJson)) {
            try {
                JSONArray people = new JSONArray(peopleJson);
                tagMembers.fromJSONArray(people);
            } catch (JSONException e) {
                Log.e("tag-view-activity", "json error refresh members - " + peopleJson, e);
            }
        }

        tagMembers.addPerson("", ""); //$NON-NLS-1$
    }

    private void uploadTagPicture(final Bitmap bitmap) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = actFmSyncService.setTagPicture(tagData.getValue(TagData.REMOTE_ID), bitmap);
                    if (TextUtils.isEmpty(url)) return;
                    if (imageCache.contains(tagData.getValue(TagData.PICTURE))) {
                        imageCache.move(tagData.getValue(TagData.PICTURE), url);
                    }
                    tagData.setValue(TagData.PICTURE, url);
                    tagData.putTransitory(SyncFlags.ACTFM_SUPPRESS_SYNC, true);
                    tagDataService.save(tagData);
                } catch (IOException e) {
                    DialogUtilities.okDialog(TagSettingsActivity.this, e.toString(), null);
                }
            }
        }).start();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if(tagMembers.getChildCount() > 1) {
            JSONArray members = tagMembers.toJSONArray();
            outState.putString(MEMBERS_IN_PROGRESS, members.toString());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        CameraResultCallback callback = new CameraResultCallback() {
            @Override
            public void handleCameraResult(Bitmap bitmap) {
                picture.setImageBitmap(bitmap);
                setBitmap = bitmap;
                if(tagData.getValue(TagData.REMOTE_ID) > 0)
                    uploadTagPicture(bitmap);
                saveTagPictureLocally(bitmap);
            }
        };
        if (ActFmCameraModule.activityResult(this, requestCode, resultCode, data, callback)) {
            // Handled
        } else if(requestCode == REQUEST_ACTFM_LOGIN && resultCode == Activity.RESULT_OK) {
            saveSettings();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem item;
        item = menu.add(Menu.NONE, MENU_DISCARD_ID, 0, R.string.TEA_menu_discard);
        item.setIcon(ThemeService.getDrawable(R.drawable.ic_menu_close));
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);

        if (isDialog) {
            item = menu.add(Menu.NONE, MENU_SAVE_ID, 0, R.string.TEA_menu_save);
            item.setIcon(ThemeService.getDrawable(R.drawable.ic_menu_save));
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
        case MENU_DISCARD_ID:
            finish();
            break;
        case MENU_SAVE_ID:
            saveSettings();
            break;
        case android.R.id.home:
            saveSettings();
            if (!isFinishing())
                finish();
            break;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void showDeleteDialog(TagData td) {
        if(td == null)
            return;

        int string;
        if (td.getValue(TagData.MEMBER_COUNT) > 0)
            string = R.string.DLG_leave_this_shared_tag_question;
        else
            string = R.string.DLG_delete_this_tag_question;


        DialogUtilities.okCancelDialog(this, getString(string, td.getValue(TagData.NAME)),
                new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                deleteTag();
            }
        }, null );
    }

    protected boolean deleteTag() {
        boolean result = tagService.deleteOrLeaveTag(this, tagData.getValue(TagData.NAME), TagService.SHOW_ACTIVE_TASKS);
        setResult(Activity.RESULT_OK);
        finish();
        return result;
    }

}
