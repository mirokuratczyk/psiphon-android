package com.psiphon3.psiphonlibrary;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.RxWorker;
import androidx.work.WorkerParameters;

import ca.psiphon.PsiphonTunnel;
import ca.psiphon.PsiphonTunnel.PsiphonTunnelFeedback;

import com.psiphon3.R;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

import net.grandcentrix.tray.AppPreferences;

/**
 * Feedback worker which securely uploads user submitted feedback to Psiphon Inc.
 *
 * Note: if an exception is thrown, then the work request will be marked as failed and will not be
 * rescheduled.
 */
public class FeedbackWorker extends RxWorker {
    private final TunnelServiceInteractor tunnelServiceInteractor;
    private final PsiphonTunnelFeedback psiphonTunnelFeedback;
    private final boolean sendDiagnosticInfo;
    private final String email;
    private final String feedbackText;
    private final String surveyResponsesJson;

    /**
     * @param appContext   The application {@link Context}
     * @param workerParams Parameters to setup the internal state of this worker
     */
    public FeedbackWorker(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);

        tunnelServiceInteractor = new TunnelServiceInteractor(getApplicationContext(), false);

        psiphonTunnelFeedback = new PsiphonTunnelFeedback();

        sendDiagnosticInfo = workerParams.getInputData().getBoolean("sendDiagnosticInfo", false);
        email = workerParams.getInputData().getString("email");
        if (email == null) {
            throw new AssertionError("feedback email null");
        }
        feedbackText = workerParams.getInputData().getString("feedbackText");
        if (feedbackText == null) {
            throw new AssertionError("feedback text null");
        }
        surveyResponsesJson = workerParams.getInputData().getString("surveyResponsesJson");
        if (surveyResponsesJson == null) {
            throw new AssertionError("survey response null");
        }
    }

    @Override
    public void onStopped() {
        super.onStopped();
        MyLog.d("FeedbackUpload: worker stopped by system");
    }

    @NonNull
    public Completable
        startSendFeedback(@NonNull Context context, @NonNull String feedbackConfigJson,
                          @NonNull String diagnosticsJson, @NonNull String uploadPath,
                          @NonNull String clientPlatformPrefix, @NonNull String clientPlatformSuffix) {

        return Completable.create(emitter -> {

            emitter.setCancellable(() -> {
                MyLog.d("FeedbackUpload: disposed, stopping feedback upload");
                psiphonTunnelFeedback.stopSendFeedback();
            });

            psiphonTunnelFeedback.startSendFeedback(
                    context,
                    new PsiphonTunnel.HostFeedbackHandler() {
                        public void sendFeedbackCompleted(java.lang.Exception e) {
                            MyLog.d("FeedbackUpload: completed");
                            if (!emitter.isDisposed()) {
                                if (e != null) {
                                    emitter.onError(e);
                                    return;
                                }
                                // Complete. This is the last callback invoked by PsiphonTunnel.
                                emitter.onComplete();
                            }
                        }
                    },
                    new PsiphonTunnel.HostLogger() {
                        public void onDiagnosticMessage(String message) {
                            if (!emitter.isDisposed()) {
                                MyLog.d("FeedbackUpload: " + message);
                            }
                        }
                    },
                    feedbackConfigJson, diagnosticsJson, uploadPath, clientPlatformPrefix,
                    clientPlatformSuffix);

        });
    }

    @NonNull
    @Override
    public Single<Result> createWork() {

        MyLog.d("FeedbackUpload: uploading feedback");

        // Note: this method is called on the main thread despite documentation stating that work
        // will be scheduled on a background thread. All work should be done off the main thread in
        // the returned signal to prevent stalling the main thread. The returned signal will be
        // disposed by the system if the work should be cancelled, e.g. the 10 minute time limit
        // elapsed. Since cleanup work is handled in the signal itself there is no need override the
        // `onStopped` method this purpose.

        // Note: this works because FeedbackWorker runs in the same process as the main activity and
        // therefore this `tunnelServiceInteractor` will receive the process-wide broadcast when
        // the VPN Service is started and subsequently bind to the VPN Service. This ensures that
        // all tunnel state changes are propagated through its tunnel state signal.
        tunnelServiceInteractor.onStart(getApplicationContext());

        return tunnelServiceInteractor
                .tunnelStateFlowable()
                .observeOn(Schedulers.io())
                .distinctUntilChanged() // Note: called upstream
                .switchMap(tunnelState -> {

                    // Note: when a new tunnel state is emitted from upstream and a previous inner
                    // signal was returned from this block, the previously returned signal will be
                    // disposed. This is the functionality that `switchMap` provides. In our case,
                    // the inner signal represents a feedback upload operation that will be
                    // cancelled if the tunnel state changes, i.e. a new value is emitted from
                    // upstream.

                    if (!tunnelState.isRunning() || tunnelState.connectionData().isConnected()) {
                        // Send feedback.

                        Context context = getApplicationContext();

                        String diagnosticData = Diagnostics.create(
                                context,
                                sendDiagnosticInfo,
                                email,
                                feedbackText,
                                surveyResponsesJson);

                        // Build a temporary tunnel config to use
                        TunnelManager.Config tunnelManagerConfig = new TunnelManager.Config();
                        final AppPreferences multiProcessPreferences = new AppPreferences(context);
                        tunnelManagerConfig.disableTimeouts = multiProcessPreferences.getBoolean(
                                context.getString(R.string.disableTimeoutsPreference), false);

                        String tunnelCoreConfig = TunnelManager.buildTunnelCoreConfig(
                                context,
                                tunnelManagerConfig,
                                !tunnelState.isRunning(),
                                null);
                        if (tunnelCoreConfig == null) {
                            return Flowable.error(new Exception("tunnel-core config null"));
                        }

                        return startSendFeedback(context, tunnelCoreConfig, diagnosticData,
                                "", "", Utils.getClientPlatformSuffix())
                                .andThen(Flowable.just(Result.success()));
                    }

                    MyLog.d("FeedbackUpload: waiting for VPN to be disconnected or connected");
                    return Flowable.empty();
                })
                .firstOrError()
                .map(__ -> Result.success())
                .doOnSuccess(__ -> MyLog.d("FeedbackUpload: upload succeeded"))
                .onErrorReturn(error -> {
                    MyLog.d("FeedbackUpload: upload failed: " + error.getMessage());
                    return Result.failure();
                });
    }
}
