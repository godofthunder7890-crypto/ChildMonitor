package com.system.service.core

import android.content.Context
import org.json.JSONObject
import org.webrtc.*

class WebRTCManager(
    private val context: Context,
    private val sendData: (type: String, payload: JSONObject) -> Unit
) {
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var eglBase: EglBase? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    companion object {
        @Volatile var instance: WebRTCManager? = null
    }

    fun start(useFront: Boolean = true) {
        stop()
        instance = this
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { name ->
            if (useFront) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
        } ?: enumerator.deviceNames.firstOrNull() ?: return

        capturer = enumerator.createCapturer(deviceName, null) ?: return
        surfaceHelper = SurfaceTextureHelper.create("WebRTCCaptureThread", eglBase!!.eglBaseContext)
        val videoSource = factory!!.createVideoSource(false)
        capturer!!.initialize(surfaceHelper, context, videoSource.capturerObserver)
        capturer!!.startCapture(640, 480, 15)

        localVideoTrack = factory!!.createVideoTrack("ARDAMSv0", videoSource)

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                sendData("ice_candidate", JSONObject().apply {
                    put("role", "from_child")
                    put("sdp_mid", candidate.sdpMid)
                    put("sdp_mline_index", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                })
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }) ?: return

        peerConnection!!.addTrack(localVideoTrack!!, listOf("stream1"))

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                peerConnection!!.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendData("webrtc_offer", JSONObject().apply { put("sdp", sdp.description) })
                    }
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun onAnswer(sdpStr: String) {
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpStr)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun stop() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        try { localVideoTrack?.dispose() } catch (_: Exception) {}
        try { peerConnection?.close() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { eglBase?.release() } catch (_: Exception) {}
        capturer = null; surfaceHelper = null; localVideoTrack = null
        peerConnection = null; factory = null; eglBase = null
        if (instance === this) instance = null
    }
}
