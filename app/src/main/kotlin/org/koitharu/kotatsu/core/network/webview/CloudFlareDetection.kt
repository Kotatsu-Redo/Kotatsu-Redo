package org.koitharu.kotatsu.core.network.webview

/**
 * Returns one of:
 *  - "ok"    — the real page is shown (no Cloudflare interstitial markers, body has content)
 *  - "error" — hard-blocked ("Attention Required" / "Access Denied" title)
 *  - "wait"  — page is empty / still loading / still showing a CF challenge
 *
 * Used to detect that a CloudFlare challenge has finished even when the `cf_clearance` cookie doesn't
 * actually change (e.g. when the WebView's fingerprint passes the challenge silently while OkHttp's
 * doesn't, so we never get an `onPageStarted` / clearance-cookie diff to signal success).
 */
internal const val CF_STATE_JS = """
	(function(){
		try {
			var href = (document.location && document.location.href) || '';
			if (href === '' || href === 'about:blank') return 'wait';
			if (document.readyState !== 'interactive' && document.readyState !== 'complete') return 'wait';
			var t = (document.title || '').toLowerCase();
			if (t.indexOf('attention required') !== -1 || t.indexOf('access denied') !== -1) return 'error';
			if (t.indexOf('just a moment') !== -1 || t.indexOf('un instant') !== -1 ||
				t.indexOf('einen moment') !== -1 || t.indexOf('un momento') !== -1 ||
				t.indexOf('один момент') !== -1) return 'wait';
			if (document.querySelector('#challenge-running, #challenge-stage, #cf-challenge-running, .cf-browser-verification, #turnstile-wrapper, #cf-please-wait, script[src*="challenge-platform"]')) return 'wait';
			if (!document.body || document.body.children.length === 0) return 'wait';
			return 'ok';
		} catch (e) { return 'wait'; }
	})()
"""
