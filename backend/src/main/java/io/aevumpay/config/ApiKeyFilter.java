package io.aevumpay.config;

import io.aevumpay.common.ApiException;
import io.aevumpay.common.ApiExceptionHandler;
import io.aevumpay.merchant.MerchantEntity;
import io.aevumpay.merchant.MerchantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String MERCHANT_ATTR = "aevum.merchant";

    private final MerchantService merchants;
    private final ApiExceptionHandler errors;

    public ApiKeyFilter(MerchantService merchants, ApiExceptionHandler errors) {
        this.merchants = merchants;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!requiresKey(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            MerchantEntity merchant = merchants.requireByApiKey(request.getHeader("X-Api-Key"));
            request.setAttribute(MERCHANT_ATTR, merchant.getId());
            filterChain.doFilter(request, response);
        } catch (ApiException exception) {
            var body = errors.handle(exception);
            response.setStatus(body.getStatusCode().value());
            response.setContentType("application/json");
            String message = body.getBody() == null ? "" : body.getBody().get("message");
            String code = body.getBody() == null ? "unauthorized" : body.getBody().get("error");
            response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + escape(message) + "\"}");
        }
    }

    static boolean requiresKey(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/v1/")) {
            return false;
        }
        if (path.startsWith("/api/v1/public/") || path.startsWith("/api/v1/auth/")) {
            return false;
        }
        if ("/api/v1/merchants".equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        return path.startsWith("/api/v1/invoices")
                || path.startsWith("/api/v1/merchants/me")
                || path.startsWith("/api/v1/webhooks");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
