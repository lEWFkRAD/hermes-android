import pytest
from tools.android_relay import (
    start_relay,
    stop_relay,
    is_relay_running,
    is_phone_connected,
    get_relay_url,
    set_pairing_code,
    _auth_is_blocked,
    _auth_record_failure,
    _auth_lock,
    _auth_blocked,
    _auth_failures,
    _AUTH_MAX_ATTEMPTS,
    _mask_token,
    _safe_body_repr,
    _decode_stream_frame,
    _cleanup_phone,
    _RelayState,
)


@pytest.fixture(autouse=True)
def reset_relay():
    yield
    stop_relay()


@pytest.fixture(autouse=True)
def reset_auth_state():
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()
    yield
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()


class TestRelayLifecycle:
    def test_start_with_specific_port(self):
        start_relay(pairing_code="TEST01", port=19876)
        assert is_relay_running()
        url = get_relay_url()
        assert "19876" in url
        stop_relay()

    def test_stop_when_not_running(self):
        stop_relay()

    def test_stop_after_failed_startup_does_not_touch_closed_loop(self, monkeypatch):
        import asyncio
        from tools import android_relay

        state = _RelayState(pairing_code="TEST01", port=19875)
        state.loop = asyncio.new_event_loop()
        state.shutdown_event = asyncio.Event()
        state.loop.close()
        monkeypatch.setattr(android_relay, "_relay_instance", state)

        stop_relay()
        assert android_relay._relay_instance is None

    def test_double_start_is_noop(self):
        start_relay(pairing_code="TEST01", port=19877)
        start_relay(pairing_code="TEST02", port=19877)
        assert is_relay_running()
        stop_relay()

    def test_is_phone_connected_false(self):
        assert not is_phone_connected()

    def test_is_relay_running_false_initially(self):
        assert not is_relay_running()

    def test_get_relay_url_returns_default_when_stopped(self):
        url = get_relay_url()
        assert url is not None
        assert "localhost" in url

    def test_set_pairing_code(self):
        set_pairing_code("NEPCODE")
        start_relay(pairing_code="NEPCODE", port=19878)
        assert is_relay_running()
        stop_relay()


class TestRateLimiting:
    def test_not_blocked_initially(self):
        assert not _auth_is_blocked("1.2.3.4")

    def test_blocked_after_max_failures(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _auth_record_failure("1.2.3.4")
        assert _auth_is_blocked("1.2.3.4")

    def test_different_ip_not_blocked(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _auth_record_failure("1.2.3.4")
        assert not _auth_is_blocked("5.6.7.8")

    def test_under_limit_not_blocked(self):
        for _ in range(_AUTH_MAX_ATTEMPTS - 1):
            _auth_record_failure("1.2.3.4")
        assert not _auth_is_blocked("1.2.3.4")


class TestTokenMasking:
    """Verify that bad auth tokens are masked in log output, not logged in plaintext."""

    def test_normal_token_masked(self):
        token = "SECRET123"
        masked = _mask_token(token)
        assert masked == "****"
        assert token not in masked

    def test_short_token_fully_masked(self):
        assert _mask_token("X") == "****"
        assert _mask_token("AB") == "****"

    def test_empty_token_fully_masked(self):
        assert _mask_token("") == "****"


class TestBodyLogRedaction:
    """Regression: request-body debug logs must not contain PII (AGENTS.md rule:
    strip phone numbers, recipients, location from tool responses/logs)."""

    def test_sms_body_redacted(self):
        repr_ = _safe_body_repr({"to": "+15551234567", "body": "secret message"})
        assert "+15551234567" not in repr_
        assert "secret message" not in repr_
        assert "<redacted>" in repr_

    def test_call_number_redacted(self):
        repr_ = _safe_body_repr({"number": "+15551234567"})
        assert "+15551234567" not in repr_

    def test_typed_text_redacted(self):
        repr_ = _safe_body_repr({"text": "hunter2-password"})
        assert "hunter2-password" not in repr_

    def test_non_sensitive_fields_kept(self):
        repr_ = _safe_body_repr({"x": 100, "y": 200})
        assert "100" in repr_ and "200" in repr_

    def test_empty_body(self):
        assert _safe_body_repr({}) == "{}"

    def test_truncated_to_200_chars(self):
        repr_ = _safe_body_repr({"key": "v" * 500})
        assert len(repr_) <= 200


class TestWsAuthHeader:
    """Regression: WS handshake auth accepts a Bearer header only. The ?token=
    query string fallback was removed because it leaked pairing codes into
    reverse-proxy access logs."""

    PORT = 19881
    CODE = "WSCODE"

    def _try_connect(self, headers=None, query=""):
        import asyncio
        import aiohttp

        async def attempt():
            async with aiohttp.ClientSession() as session:
                try:
                    ws = await session.ws_connect(
                        f"ws://127.0.0.1:{self.PORT}/ws{query}", headers=headers or {}
                    )
                    await ws.close()
                    return True
                except aiohttp.WSServerHandshakeError:
                    return False

        return asyncio.run(attempt())

    def test_bearer_header_accepted(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert self._try_connect(headers={"Authorization": f"Bearer {self.CODE}"})

    def test_query_token_rejected(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert not self._try_connect(query=f"?token={self.CODE}")

    def test_bad_bearer_header_rejected(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert not self._try_connect(headers={"Authorization": "Bearer WRONG1"})

    def test_no_credentials_rejected(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert not self._try_connect()


class TestMicrophoneBinaryStream:
    PORT = 19882
    CODE = "MICODE"

    @staticmethod
    def _frame(request_id: str, payload: bytes) -> bytes:
        request_id_bytes = request_id.encode("utf-8")
        return len(request_id_bytes).to_bytes(2, "big") + request_id_bytes + payload

    def test_binary_frame_decoder(self):
        raw = self._frame("request-1", b"audio")
        assert _decode_stream_frame(raw) == ("request-1", b"audio")

    @pytest.mark.parametrize("raw", [b"", b"\x00\x00x", b"\x00\x05abc"])
    def test_binary_frame_decoder_rejects_malformed_frames(self, raw):
        with pytest.raises(ValueError):
            _decode_stream_frame(raw)

    def test_replaced_socket_cannot_clean_up_new_phone(self):
        import asyncio

        async def scenario():
            state = _RelayState(pairing_code=self.CODE, port=self.PORT)
            state.phone_ws_lock = asyncio.Lock()
            old_socket = object()
            new_socket = object()
            state.phone_ws = new_socket

            await _cleanup_phone(
                state,
                reason="old socket disconnected",
                expected_ws=old_socket,
            )

            assert state.phone_ws is new_socket

        asyncio.run(scenario())

    def test_wav_is_streamed_from_phone_to_http_client(self):
        import asyncio
        import hashlib
        import aiohttp

        wav = b"RIFF" + (b"\x01\x02" * 64)
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            headers = {"Authorization": f"Bearer {self.CODE}"}
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers=headers,
                )
                download = asyncio.create_task(
                    session.get(
                        f"http://127.0.0.1:{self.PORT}/mic_file",
                        headers=headers,
                    )
                )

                command = await ws.receive_json(timeout=2)
                request_id = command["request_id"]
                assert command["path"] == "/mic_file"

                await ws.send_json(
                    {
                        "request_id": request_id,
                        "status": 200,
                        "stream": {
                            "event": "start",
                            "filename": "recording_test.wav",
                            "mimeType": "audio/wav",
                            "size": len(wav),
                        },
                    }
                )
                await ws.send_bytes(self._frame(request_id, wav[:48]))
                await ws.send_bytes(self._frame(request_id, wav[48:]))
                await ws.send_json(
                    {
                        "request_id": request_id,
                        "status": 200,
                        "stream": {
                            "event": "end",
                            "bytes": len(wav),
                            "sha256": hashlib.sha256(wav).hexdigest(),
                        },
                    }
                )

                response = await asyncio.wait_for(download, timeout=2)
                assert response.status == 200
                assert response.headers["Content-Type"] == "audio/wav"
                assert await response.read() == wav
                await ws.close()

        asyncio.run(scenario())


class TestPhoneReplacement:
    PORT = 19883
    CODE = "REPLCE"

    def test_new_phone_remains_usable_after_replacing_old_socket(self):
        import asyncio
        import aiohttp

        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            headers = {"Authorization": f"Bearer {self.CODE}"}
            async with aiohttp.ClientSession() as session:
                old_ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers=headers,
                )
                old_close = asyncio.create_task(old_ws.receive())
                new_ws = await asyncio.wait_for(
                    session.ws_connect(
                        f"ws://127.0.0.1:{self.PORT}/ws",
                        headers=headers,
                    ),
                    timeout=2,
                )
                await asyncio.wait_for(old_close, timeout=2)

                request = asyncio.create_task(
                    session.get(
                        f"http://127.0.0.1:{self.PORT}/ping",
                        headers=headers,
                    )
                )
                command = await new_ws.receive_json(timeout=2)
                await new_ws.send_json(
                    {
                        "request_id": command["request_id"],
                        "status": 200,
                        "result": {"status": "ok"},
                    }
                )

                response = await asyncio.wait_for(request, timeout=2)
                assert response.status == 200
                assert await response.json() == {"status": "ok"}
                await new_ws.close()

        asyncio.run(scenario())


class TestMicrophoneBinaryStreamNegative:
    """Negative paths for the binary stream relay (#99).

    The happy path (TestMicrophoneBinaryStream) covers a well-behaved phone.
    These tests pin the relay's behavior against a hostile or failing phone:
    checksum mismatch, oversize payloads, length-mismatched end events, an
    invalid declared size, and a mid-stream disconnect. In every case the
    HTTP caller must get either an error status or a torn-down connection —
    never a silent partial file presented as complete.
    """

    PORT = 19884
    CODE = "MINEG1"

    @staticmethod
    def _frame(request_id: str, payload: bytes) -> bytes:
        request_id_bytes = request_id.encode("utf-8")
        return len(request_id_bytes).to_bytes(2, "big") + request_id_bytes + payload

    def _run_stream_scenario(self, phone_play, expect):
        import asyncio
        import aiohttp

        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            headers = {"Authorization": f"Bearer {self.CODE}"}
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers=headers,
                )
                download = asyncio.create_task(
                    session.get(
                        f"http://127.0.0.1:{self.PORT}/mic_file",
                        headers=headers,
                    )
                )
                command = await ws.receive_json(timeout=2)
                assert command["path"] == "/mic_file"

                await phone_play(ws, command["request_id"])

                if expect == "status":
                    response = await asyncio.wait_for(download, timeout=2)
                    assert response.status >= 400
                    await ws.close()
                    return
                if expect == "teardown-or-complete":
                    # Detection happens after the last byte is delivered; the
                    # client either sees a torn-down body or the full payload.
                    # Server-side logs are asserted by the caller.
                    response = await asyncio.wait_for(download, timeout=2)
                    try:
                        await asyncio.wait_for(response.read(), timeout=2)
                    except aiohttp.ClientError:
                        pass
                    await ws.close()
                    return
                # expect == "teardown": headers may already be sent, but the
                # body read must fail — never a silent truncated success.
                try:
                    response = await asyncio.wait_for(download, timeout=2)
                    with pytest.raises(aiohttp.ClientError):
                        await asyncio.wait_for(response.read(), timeout=2)
                except aiohttp.ClientError:
                    pass  # connection died before/with headers — also acceptable
                await ws.close()

        asyncio.run(scenario())

    def test_sha256_mismatch_is_detected_server_side(self, caplog):
        import hashlib
        import logging

        wav = b"RIFF" + (b"\x09\x09" * 64)

        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_bad.wav",
                        "mimeType": "audio/wav",
                        "size": len(wav),
                    },
                }
            )
            await ws.send_bytes(self._frame(request_id, wav))
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "end",
                        "bytes": len(wav),
                        # Wrong digest over different content.
                        "sha256": hashlib.sha256(b"tampered").hexdigest(),
                    },
                }
            )

        # The full body has already been streamed when the checksum is
        # verified, so the HTTP client cannot observe the abort — pin the
        # server-side detection instead: the relay must log the mismatch and
        # force-close rather than complete the stream cleanly.
        with caplog.at_level(logging.WARNING, logger="tools.android_relay"):
            self._run_stream_scenario(phone_play, expect="teardown-or-complete")
        assert any("checksum mismatch" in r.message for r in caplog.records)

    def test_oversize_chunk_aborts_the_download(self):
        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_big.wav",
                        "mimeType": "audio/wav",
                        "size": 8,  # declare 8 bytes...
                    },
                }
            )
            # ...then send 48.
            await ws.send_bytes(self._frame(request_id, b"\x01" * 48))

        self._run_stream_scenario(phone_play, expect="teardown")

    def test_end_bytes_mismatch_is_detected_server_side(self, caplog):
        import hashlib
        import logging

        wav = b"RIFF" + (b"\x02\x02" * 32)

        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_len.wav",
                        "mimeType": "audio/wav",
                        "size": len(wav),
                    },
                }
            )
            await ws.send_bytes(self._frame(request_id, wav))
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "end",
                        "bytes": len(wav) + 100,  # lies about the total
                        "sha256": hashlib.sha256(wav).hexdigest(),
                    },
                }
            )

        # Same shape as the checksum case: the payload is fully delivered
        # before the end event lies, so pin the server-side detection.
        with caplog.at_level(logging.WARNING, logger="tools.android_relay"):
            self._run_stream_scenario(phone_play, expect="teardown-or-complete")
        assert any(
            "length does not match" in r.message for r in caplog.records
        )

    def test_declared_size_above_the_cap_is_rejected_up_front(self):
        from tools.android_relay import _MAX_STREAM_BYTES

        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_huge.wav",
                        "mimeType": "audio/wav",
                        "size": _MAX_STREAM_BYTES + 1,
                    },
                }
            )

        self._run_stream_scenario(phone_play, expect="status")

    def test_negative_declared_size_is_rejected_up_front(self):
        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_neg.wav",
                        "mimeType": "audio/wav",
                        "size": -1,
                    },
                }
            )

        self._run_stream_scenario(phone_play, expect="status")

    def test_phone_disconnect_mid_stream_tears_down_the_download(self):
        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_cut.wav",
                        "mimeType": "audio/wav",
                        "size": 1024,
                    },
                }
            )
            await ws.send_bytes(self._frame(request_id, b"\x03" * 48))
            await ws.close()  # phone vanishes mid-stream

        self._run_stream_scenario(phone_play, expect="teardown")
