package org.koitharu.kotatsu.core.network.webview

/**
 * Returns one of:
 *  - "ok"    — the real page is shown (no Cloudflare interstitial markers, body has content, which
 *                may be nothing but text: an ajax endpoint answering "0" is a loaded page)
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
			var challengeNodes = document.querySelectorAll(
				'#challenge-running, #challenge-stage, #cf-challenge-running, ' +
				'.cf-browser-verification, #turnstile-wrapper, #cf-please-wait'
			);
			for (var i = 0; i < challengeNodes.length; i++) {
				var node = challengeNodes[i];
				var style = window.getComputedStyle(node);
				if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') continue;
				var rect = node.getBoundingClientRect();
				if (rect.width > 0 && rect.height > 0) return 'wait';
			}
			var body = document.body;
			if (!body) return 'wait';
			// An endpoint may legitimately answer with a bare text node - WordPress admin-ajax.php
			// replies with "0" - and children counts elements only, so an empty element list is not
			// proof the page is still loading. Only treat it as unfinished when there is no text either.
			if (body.children.length === 0 && (body.textContent || '').trim().length === 0) return 'wait';
			return 'ok';
		} catch (e) { return 'wait'; }
	})()
"""
