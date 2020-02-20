/*
 * Copyright (c) 2016, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;

import com.psiphon3.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

@TargetApi(Build.VERSION_CODES.HONEYCOMB)
class InstalledAppsMultiSelectListPreference extends AlertDialog.Builder {
    private final AppExclusionsManager appExclusionsManager;
    private final InstalledAppsRecyclerViewAdapter adapter;
    private final boolean whitelist;

    private final int installedAppsCount;

    InstalledAppsMultiSelectListPreference(Context context, LayoutInflater layoutInflater, boolean whitelist) {
        super(context);
        this.whitelist = whitelist;
        appExclusionsManager = new AppExclusionsManager(context);
        List<AppEntry> installedApps = getInstalledApps(context);
        installedAppsCount = installedApps.size();
        final Set<String> selectedApps = whitelist ?
                appExclusionsManager.getCurrentAppsIncludedInVpn() :
                appExclusionsManager.getCurrentAppsExcludedFromVpn();

        adapter = new InstalledAppsRecyclerViewAdapter(
                context,
                installedApps,
                selectedApps);

        setTitle(getTitle(whitelist));
        setView(getView(context, layoutInflater, whitelist));
        setPositiveButton(R.string.abc_action_mode_done , null);
        setCancelable(true);
        setNegativeButton(android.R.string.cancel, null);
    }

    public boolean isWhitelist() {
        return whitelist;
    }

    public Set<String> getSelectedApps() {
        return adapter.getSelectedApps();
    }

    public int getInstalledAppsCount() {
        return installedAppsCount;
    }

    private int getTitle(boolean whitelist) {
        return whitelist ? R.string.preference_routing_include_apps_title : R.string.preference_routing_exclude_apps_title;
    }

    private View getView(Context context, LayoutInflater layoutInflater, final boolean whitelist) {
        View view = layoutInflater.inflate(R.layout.dialog_list_preference, null);
        adapter.setClickListener(new InstalledAppsRecyclerViewAdapter.ItemClickListener() {
            @SuppressLint("ApplySharedPref")
            @Override
            public void onItemClick(View view, int position) {
                String app = adapter.getItem(position).getPackageId();

                // try to remove the app, if not able, i.e. it wasn't in the set, add it
                if (!adapter.getSelectedApps().remove(app)) {
                    adapter.getSelectedApps().add(app);
                }
            }
        });

        RecyclerView recyclerView = (RecyclerView) view.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(adapter);

        return view;
    }

    private List<AppEntry> getInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();

        List<AppEntry> apps = new ArrayList<>();
        List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);


        String selfPackageName = context.getPackageName();
        boolean checkSelf = true;

        for (int i = 0; i < packages.size(); i++) {
            PackageInfo p = packages.get(i);

            // The returned app list excludes:
            //  - Apps that don't require internet access
            //  - Psiphon itself
            if (checkSelf && p.packageName.equals(selfPackageName)) {
                // Skip the check next time once we got a match.
                checkSelf = false;
                continue;
            }
            if (isInternetPermissionGranted(p)) {
                // This takes a bit of time, but since we want the apps sorted by displayed name
                // its best to do synchronously
                String appName = p.applicationInfo.loadLabel(pm).toString();
                String packageId = p.packageName;
                Single<Drawable> iconLoader = getIconLoader(p.applicationInfo, pm);
                apps.add(new AppEntry(appName, packageId, iconLoader));
            }
        }

        Collections.sort(apps);
        return apps;
    }

    private boolean isSystemPackage(PackageInfo pkgInfo) {
        return ((pkgInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
    }

    private boolean isInternetPermissionGranted(PackageInfo pkgInfo) {
        if (pkgInfo.requestedPermissions != null) {
            for (String permission : pkgInfo.requestedPermissions) {
                if (Manifest.permission.INTERNET.equals(permission)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Single<Drawable> getIconLoader(final ApplicationInfo applicationInfo, final PackageManager packageManager) {
        return Single.<Drawable>create(emitter -> {
            Drawable icon = applicationInfo.loadIcon(packageManager);
            if (!emitter.isDisposed()) {
                emitter.onSuccess(icon);
            }
        })
                // shouldn't ever get an error but handle it just in case
                .doOnError(e -> Utils.MyLog.g("failed to load icon for " + applicationInfo.packageName + " " + e))
                // run on io as we're reading off disk
                .subscribeOn(Schedulers.io())
                // observe on ui
                .observeOn(AndroidSchedulers.mainThread());
    }
}