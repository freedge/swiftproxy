/*
 * Copyright (c) Bounce Storage, Inc. All rights reserved.
 * For more information, please see COPYRIGHT in the top-level directory.
 */

package com.bouncestorage.swiftproxy.v1;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.annotation.JsonProperty;

@Path("/info")
public final class InfoResource {
    private static final ServerConfiguration SERVER_CONFIG = new ServerConfiguration();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ServerConfiguration getInfo() {
        return SERVER_CONFIG;
    }

    static final class ServerConfiguration {
        @JsonProperty SwiftConfiguration swift = new SwiftConfiguration();
        @JsonProperty SLOConfiguration slo = new SLOConfiguration();

        static final class SwiftConfiguration {
            @JsonProperty final int account_listing_limit = 10000;
            @JsonProperty final boolean allow_account_management = false;
            @JsonProperty final int container_listing_limit = 10000;
            @JsonProperty final int max_account_name_length = 256;
            @JsonProperty final int max_container_name_length = 256;
            @JsonProperty final long max_file_size = 5368709122L;
            @JsonProperty final int max_header_size = 8192;
            @JsonProperty final int max_meta_name_length = 128;
            @JsonProperty final int max_meta_value_length = 256;
            @JsonProperty final int max_meta_count = 90;
            @JsonProperty final int max_meta_overall_size = 2048;
            @JsonProperty final int max_object_name_length = 1024;
            @JsonProperty final boolean strict_cors_mode = true;
        }

        static final class SLOConfiguration {
            @JsonProperty int max_manifest_segments = 1000;
        }
    }
}