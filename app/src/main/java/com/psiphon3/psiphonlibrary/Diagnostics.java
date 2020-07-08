/*
 * Copyright (c) 2015, Psiphon Inc.
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

import java.security.SecureRandom;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import ca.psiphon.PsiphonTunnel;

import com.psiphon3.psiphonlibrary.Utils.MyLog;

import com.psiphon3.R;

import net.grandcentrix.tray.AppPreferences;

public class Diagnostics
{
    /**
     * Create the diagnostic info package.
     * @param context
     * @param sendDiagnosticInfo
     * @param email
     * @param feedbackText
     * @param surveyResponsesJson
     * @return A String containing the diagnostic info, or `null` if there is
     *         an error.
     */
    static public String create(
                            Context context,
                            boolean sendDiagnosticInfo,
                            String email,
                            String feedbackText,
                            String surveyResponsesJson)
    {
        // Our attachment is JSON, which is then encrypted, and the
        // encryption elements stored in JSON.

        String diagnosticJSON;

        try
        {
            /*
             * Metadata
             */

            JSONObject metadata = new JSONObject();

            metadata.put("platform", "android");
            metadata.put("version", 4);

            SecureRandom rnd = new SecureRandom();
            byte[] id = new byte[8];
            rnd.nextBytes(id);
            metadata.put("id", Utils.byteArrayToHexString(id));

            /*
             * System Information
             */

            JSONObject sysInfo_Build = new JSONObject();
            sysInfo_Build.put("BRAND", Build.BRAND);
            sysInfo_Build.put("CPU_ABI", Build.CPU_ABI);
            sysInfo_Build.put("MANUFACTURER", Build.MANUFACTURER);
            sysInfo_Build.put("MODEL", Build.MODEL);
            sysInfo_Build.put("DISPLAY", Build.DISPLAY);
            sysInfo_Build.put("TAGS", Build.TAGS);
            sysInfo_Build.put("VERSION__CODENAME", Build.VERSION.CODENAME);
            sysInfo_Build.put("VERSION__RELEASE", Build.VERSION.RELEASE);
            sysInfo_Build.put("VERSION__SDK_INT", Build.VERSION.SDK_INT);

            JSONObject sysInfo_psiphonEmbeddedValues = new JSONObject();
            sysInfo_psiphonEmbeddedValues.put("PROPAGATION_CHANNEL_ID", EmbeddedValues.PROPAGATION_CHANNEL_ID);
            sysInfo_psiphonEmbeddedValues.put("SPONSOR_ID", EmbeddedValues.SPONSOR_ID);
            sysInfo_psiphonEmbeddedValues.put("CLIENT_VERSION", EmbeddedValues.CLIENT_VERSION);

            JSONObject sysInfo = new JSONObject();
            sysInfo.put("isRooted", Utils.isRooted());
            sysInfo.put("isPlayStoreBuild", EmbeddedValues.IS_PLAY_STORE_BUILD);
            sysInfo.put("language", Locale.getDefault().getLanguage());
            sysInfo.put("networkTypeName", Utils.getNetworkTypeName(context));
            sysInfo.put("Build", sysInfo_Build);
            sysInfo.put("PsiphonInfo", sysInfo_psiphonEmbeddedValues);

            /*
             * Diagnostic History
             */

            JSONArray diagnosticHistory = new JSONArray();

            for (StatusList.DiagnosticEntry item : StatusList.cloneDiagnosticHistory())
            {
                JSONObject entry = new JSONObject();
                entry.put("timestamp!!timestamp", Utils.getISO8601String(item.timestamp()));
                entry.put("msg", item.msg() == null ? JSONObject.NULL : item.msg());
                entry.put("data", item.data() == null ? JSONObject.NULL : item.data());
                diagnosticHistory.put(entry);
            }

            /*
             * Status History
             */

            JSONArray statusHistory = new JSONArray();

            for (StatusList.StatusEntry internalEntry : StatusList.cloneStatusHistory())
            {
                // Don't send any sensitive logs or debug logs
                if (internalEntry.sensitivity() == MyLog.Sensitivity.SENSITIVE_LOG
                    || internalEntry.priority() == Log.DEBUG)
                {
                    continue;
                }

                JSONObject statusEntry = new JSONObject();

                String idName = context.getResources().getResourceEntryName(internalEntry.stringId());
                statusEntry.put("id", idName);
                statusEntry.put("timestamp!!timestamp", Utils.getISO8601String(internalEntry.timestamp()));
                statusEntry.put("priority", internalEntry.priority());
                statusEntry.put("formatArgs", JSONObject.NULL);
                statusEntry.put("throwable", JSONObject.NULL);

                if (internalEntry.formatArgs() != null && internalEntry.formatArgs().length > 0
                    // Don't send any sensitive format args
                    && internalEntry.sensitivity() != MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS)
                {
                    JSONArray formatArgs = new JSONArray();
                    for (Object o : internalEntry.formatArgs())
                    {
                        formatArgs.put(o);
                    }
                    statusEntry.put("formatArgs", formatArgs);
                }

                if (internalEntry.throwable() != null)
                {
                    JSONObject throwable = new JSONObject();

                    throwable.put("message", internalEntry.throwable().toString());

                    JSONArray stack = new JSONArray();
                    for (StackTraceElement element : internalEntry.throwable().getStackTrace())
                    {
                        stack.put(element.toString());
                    }
                    throwable.put("stack", stack);

                    statusEntry.put("throwable", throwable);
                }

                statusHistory.put(statusEntry);
            }

            /*
             * JSON-ify the diagnostic info
             */

            JSONObject diagnosticInfo = new JSONObject();
            diagnosticInfo.put("SystemInformation", sysInfo);
            diagnosticInfo.put("DiagnosticHistory", diagnosticHistory);
            diagnosticInfo.put("StatusHistory", statusHistory);

            JSONObject diagnosticObject = new JSONObject();
            diagnosticObject.put("Metadata", metadata);

            if (sendDiagnosticInfo)
            {
                diagnosticObject.put("DiagnosticInfo", diagnosticInfo);
            }

            if (feedbackText.length() > 0 || surveyResponsesJson.length() > 0)
            {
                JSONObject feedbackInfo = new JSONObject();
                feedbackInfo.put("email", email);

                JSONObject feedbackMessageInfo = new JSONObject();
                feedbackMessageInfo.put("text", feedbackText);
                feedbackInfo.put("Message", feedbackMessageInfo);

                JSONObject feedbackSurveyInfo = new JSONObject();
                feedbackSurveyInfo.put("json", surveyResponsesJson);
                feedbackInfo.put("Survey", feedbackSurveyInfo);

                diagnosticObject.put("Feedback",  feedbackInfo);
            }

            diagnosticJSON = diagnosticObject.toString();
        }
        catch (JSONException e)
        {
            throw new RuntimeException(e);
        }

        assert(diagnosticJSON != null);

        return diagnosticJSON;
    }

    /**
     * Create the diagnostic data package and upload it.
     * @param context
     * @param sendDiagnosticInfo
     * @param email
     * @param feedbackText
     * @param surveyResponsesJson
     * @return
     */
    static public void send(
            Context context,
            boolean sendDiagnosticInfo,
            String email,
            String feedbackText,
            String surveyResponsesJson)
    {
        // Fire-and-forget thread.
        class FeedbackRequestThread extends Thread
        {
            private final Context mContext;
            private final boolean mSendDiagnosticInfo;
            private final String mEmail;
            private final String mFeedbackText;
            private final String mSurveyResponsesJson;

            FeedbackRequestThread(
                    Context context,
                    boolean sendDiagnosticInfo,
                    String email,
                    String feedbackText,
                    String surveyResponsesJson)
            {
                mContext = context;
                mSendDiagnosticInfo = sendDiagnosticInfo;
                mEmail = email;
                mFeedbackText = feedbackText;
                mSurveyResponsesJson = surveyResponsesJson;
            }

            @Override
            public void run()
            {
                String diagnosticData = Diagnostics.create(
                        mContext,
                        mSendDiagnosticInfo,
                        mEmail,
                        mFeedbackText,
                        mSurveyResponsesJson);

                if (diagnosticData == null)
                {
                    return;
                }

                // Build a temporary tunnel config to use
                TunnelManager.Config tunnelManagerConfig = new TunnelManager.Config();
                final AppPreferences multiProcessPreferences = new AppPreferences(context);
                tunnelManagerConfig.disableTimeouts = multiProcessPreferences.getBoolean(
                        context.getString(R.string.disableTimeoutsPreference), false);

                String tunnelCoreConfig = TunnelManager.buildTunnelCoreConfig(
                        context,
                        tunnelManagerConfig,
                        "feedbackupload");

                if (tunnelCoreConfig == null) {
                    MyLog.g("Diagnostics tunnelCoreConfig null");
                    return;
                }

                try {
                    PsiphonTunnel.sendFeedback(
                            tunnelCoreConfig,
                            diagnosticData,
                            EmbeddedValues.FEEDBACK_ENCRYPTION_PUBLIC_KEY,
                            EmbeddedValues.FEEDBACK_DIAGNOSTIC_INFO_UPLOAD_SERVER,
                            EmbeddedValues.FEEDBACK_DIAGNOSTIC_INFO_UPLOAD_PATH,
                            EmbeddedValues.FEEDBACK_DIAGNOSTIC_INFO_UPLOAD_SERVER_HEADERS);
                } catch (java.lang.Exception e) {
                    MyLog.g(String.format("Diagnostics sendFeedback failed: %s", e.getMessage()));
                }
            }
        }

        FeedbackRequestThread thread = new FeedbackRequestThread(
                                            context,
                                            sendDiagnosticInfo,
                                            email,
                                            feedbackText,
                                            surveyResponsesJson);
        thread.start();
    }

}
