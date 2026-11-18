package com.lingmao.scm.security;

import java.io.IOException;

public final class RequestBodyTooLarge extends IOException {
    public RequestBodyTooLarge() { super("Request body limit exceeded"); }
}
