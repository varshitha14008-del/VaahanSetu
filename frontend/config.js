// VaahanSetu Frontend API Resolver
(function () {
    // 1. Check if URL contains ?api=... query parameter (e.g. ?api=http://localhost:8080/api)
    const urlParams = new URLSearchParams(window.location.search);
    const queryApi = urlParams.get('api');
    if (queryApi) {
        const cleanApi = queryApi.replace(/\/+$/, '');
        localStorage.setItem('VAAHAN_API_URL', cleanApi);
        window.VAAHAN_API_URL = cleanApi;
        console.log('[VaahanSetu] Set API URL from query parameter:', cleanApi);
        return;
    }

    const isLocal = !window.location.hostname ||
        window.location.protocol === 'file:' ||
        window.location.hostname === 'localhost' ||
        window.location.hostname === '127.0.0.1' ||
        window.location.hostname === '0.0.0.0' ||
        window.location.hostname === '::1';

    // 2. Local development (localhost, 127.0.0.1, 0.0.0.0, or file://)
    if (isLocal) {
        const savedApi = localStorage.getItem('VAAHAN_API_URL');
        // Stale or unreachable cloud URLs in localStorage should not override local development
        if (savedApi && !savedApi.includes('onrender.com')) {
            window.VAAHAN_API_URL = savedApi;
            console.log('[VaahanSetu] Using local API URL from localStorage:', window.VAAHAN_API_URL);
            return;
        }
        window.VAAHAN_API_URL = 'http://localhost:8080/api';
        return;
    }

    // 3. Check localStorage override for hosted environments
    const savedApi = localStorage.getItem('VAAHAN_API_URL');
    if (savedApi) {
        window.VAAHAN_API_URL = savedApi;
        console.log('[VaahanSetu] Using API URL from localStorage:', window.VAAHAN_API_URL);
        return;
    }

    // 4. Render cloud hosting auto-detection
    // If the frontend is on Render (e.g. vaahansetu-portal.onrender.com),
    // target the live backend web service (vaahansetu-api.onrender.com)
    if (window.location.hostname.endsWith('.onrender.com')) {
        let apiHost = window.location.hostname.replace('-portal', '-api');
        if (!apiHost.includes('-api')) {
            apiHost = 'vaahansetu-api.onrender.com';
        }
        window.VAAHAN_API_URL = `https://${apiHost}/api`;
        console.log('[VaahanSetu] Auto-detected Render backend API URL:', window.VAAHAN_API_URL);
        return;
    }

    // 5. Default production backend
    window.VAAHAN_API_URL = `${window.location.origin}/api`;
})();
