/*
 * Copyright 2015 Bounce Storage, Inc. <info@bouncestorage.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bouncestorage.swiftproxy.v1;

import static java.util.Objects.requireNonNull;

import static com.google.common.base.Throwables.propagate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.inject.Singleton;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

import com.bouncestorage.swiftproxy.BlobStoreResource;
import com.bouncestorage.swiftproxy.BounceResourceConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;

import org.glassfish.grizzly.http.server.Request;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.ContainerNotFoundException;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.util.Strings2;

@Singleton
@Path("/v1/{account}")
public final class AccountResource extends BlobStoreResource {
    private static final Iterable<Character> skipPathEncoding = Lists.charactersOf("/:;=");

    @GET
    public Response getAccount(@NotNull @PathParam("account") String account,
                               @QueryParam("limit") Optional<Integer> limit,
                               @QueryParam("marker") Optional<String> marker,
                               @QueryParam("end_marker") Optional<String> endMarker,
                               @QueryParam("format") Optional<String> format,
                               @QueryParam("prefix") Optional<String> prefix,
                               @QueryParam("delimiter") Optional<String> delimiter,
                               @HeaderParam("X-Auth-Token") String authToken,
                               @HeaderParam("X-Newest") @DefaultValue("false") boolean newest,
                               @HeaderParam("Accept") Optional<String> accept) {
        delimiter.ifPresent(x -> logger.info("delimiter not supported yet"));

        BlobStore blobStore = getBlobStore(authToken).get();
        ArrayList<ContainerEntry> entries = blobStore.list()
                .stream()
                .map(StorageMetadata::getName)
                .filter(name -> marker.map(m -> name.compareTo(m) > 0).orElse(true))
                .filter(name -> endMarker.map(m -> name.compareTo(m) < 0).orElse(true))
                .filter(name -> prefix.map(name::startsWith).orElse(true))
                .map(ContainerEntry::new)
                .collect(Collectors.toCollection(ArrayList::new));

        MediaType formatType;
        if (format.isPresent()) {
            formatType = BounceResourceConfig.getMediaType(format.get());
        } else if (accept.isPresent()) {
            formatType = MediaType.valueOf(accept.get());
        } else {
            formatType = MediaType.TEXT_PLAIN_TYPE;
        }

        if (blobStore.getContext().unwrap().getId().equals("transient")) {
            entries.sort((a, b) -> a.getName().compareTo(b.getName()));
        }

        long count = entries.size();
        limit.ifPresent((max) -> {
            if (entries.size() > max) {
                entries.subList(max, entries.size()).clear();
            }
        });

        Account root = new Account();
        root.name = account;
        root.container = entries;
        return output(root, entries, formatType)
                .header("X-Account-Container-Count", count)
                .header("X-Account-Object-Count", -1)
                .header("X-Account-Bytes-Used", -1)
                .header("X-Timestamp", -1)
                .header("X-Trans-Id", -1)
                .header("Accept-Ranges", "bytes")
                .build();
    }

    @HEAD
    public Response headAccount(@NotNull @PathParam("account") String account,
                                @HeaderParam("X-Auth-Token") String authToken,
                                @HeaderParam("X-Newest") boolean newest) {
        return Response.noContent()
                .header("X-Account-Container-Count", -1)
                .header("X-Account-Object-Count", -1)
                .header("X-Account-Bytes-Used", -1)
                .header("X-Timestamp", -1)
                .header("X-Trans-Id", -1)
                .header("Accept-Ranges", "bytes")
                .build();
    }

    @DELETE
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public BulkDeleteResult bulkDeleteDelete(@NotNull @PathParam("account") String account,
                                             @QueryParam("bulk-delete") String bulkDelete,
                                             @HeaderParam("X-Auth-Token") String authToken,
                                             @Context Request request) throws JsonProcessingException {
        return bulkDelete(account, bulkDelete, authToken, request);
    }

    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public BulkDeleteResult bulkDelete(@NotNull @PathParam("account") String account,
                                       @QueryParam("bulk-delete") String bulkDelete,
                                       @HeaderParam("X-Auth-Token") String authToken,
                                       @Context Request request) throws JsonProcessingException {
        if (bulkDelete == null) {
            // TODO: Currently this will match the account delete request as well, which we do not implement
            throw new WebApplicationException(Response.Status.NOT_IMPLEMENTED);
        }

        BlobStore blobStore = getBlobStore(authToken).get();
        if (blobStore == null) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        String line;
        ArrayList<String> objects = new ArrayList<>();

        boolean isTransient = blobStore.getContext().unwrap().getId().equals("transient");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
            while ((line = in.readLine()) != null) {
                if (isTransient) {
                    // jclouds does not escape things correctly
                    line = Strings2.urlEncode(line, skipPathEncoding);
                }
                objects.add(line);
            }
        } catch (IOException e) {
            throw propagate(e);
        }

        BulkDeleteResult result = new BulkDeleteResult();
        for (String objectContainer : objects) {
            try {
                if (objectContainer.startsWith("/")) {
                    objectContainer = objectContainer.substring(1);
                }
                int separatorIndex = objectContainer.indexOf('/');
                if (separatorIndex < 0) {
                    blobStore.deleteContainer(objectContainer.substring(1));
                    result.numberDeleted += 1;
                    continue;
                }
                String container = objectContainer.substring(0, separatorIndex);
                String object = objectContainer.substring(separatorIndex + 1);

                if (!blobStore.blobExists(container, object)) {
                    result.numberNotFound += 1;
                } else {
                    blobStore.removeBlob(container, object);
                    result.numberDeleted += 1;
                }
            } catch (ContainerNotFoundException e) {
                result.numberNotFound += 1;
            } catch (Exception e) {
                e.printStackTrace();
                result.errors.add(objectContainer);
            }
        }

        if (result.errors.isEmpty()) {
            result.responseStatus = Response.Status.OK.toString();
            return result;
        } else {
            ObjectMapper mapper = new ObjectMapper();
            result.responseStatus = Response.Status.BAD_GATEWAY.toString();
            throw new WebApplicationException(mapper.writeValueAsString(result), Response.Status.BAD_GATEWAY);
        }
    }

    @XmlRootElement(name = "account")
    @XmlType
    static class Account {
        @XmlElement
        List<ContainerEntry> container;
        @XmlAttribute
        private String name;
    }

    @XmlRootElement(name = "container")
    @XmlType
    static class ContainerEntry {
        @XmlElement
        private String name;
        @XmlElement
        private long count;
        @XmlElement
        private long bytes;

        // for jackson XML
        public ContainerEntry() {

        }

        @JsonCreator
        public ContainerEntry(@JsonProperty("name") String name) {
            this.name = requireNonNull(name);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ContainerEntry &&
                    name.equals(((ContainerEntry) other).name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, count, bytes);
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static class BulkDeleteResult {
        @JsonProperty("Response Status")
        String responseStatus;
        @JsonProperty("Errors")
        ArrayList<String> errors;
        @JsonProperty("Number Deleted")
        int numberDeleted;
        @JsonProperty("Number Not Found")
        int numberNotFound;

        BulkDeleteResult() {
            errors = new ArrayList<>();
            numberDeleted = 0;
            numberNotFound = 0;
        }
    }
}
