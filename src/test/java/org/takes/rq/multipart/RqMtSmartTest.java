/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014-2019 Yegor Bugayenko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.takes.rq.multipart;

import com.jcabi.http.request.JdkRequest;
import com.jcabi.http.response.RestResponse;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;
import org.apache.commons.lang.StringUtils;
import org.cactoos.text.Joined;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.takes.Request;
import org.takes.Response;
import org.takes.Take;
import org.takes.http.FtRemote;
import org.takes.misc.PerformanceTests;
import org.takes.rq.RqFake;
import org.takes.rq.RqPrint;
import org.takes.rq.TempInputStream;
import org.takes.rs.RsText;

/**
 * Test case for {@link RqMtSmart}.
 * @since 0.33
 * @checkstyle MultipleStringLiteralsCheck (500 lines)
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 */
public final class RqMtSmartTest {
    /**
     * Body element.
     */
    private static final String BODY_ELEMENT = "--zzz";

    /**
     * Content type.
     */
    private static final String CONTENT_TYPE =
        "Content-Type: multipart/form-data; boundary=zzz";

    /**
     * Carriage return constant.
     */
    private static final String CRLF = "\r\n";

    /**
     * Content disposition.
     */
    private static final String DISPOSITION = "Content-Disposition";

    /**
     * Content disposition plus form data.
     */
    private static final String CONTENT = String.format(
        "%s: %s", RqMtSmartTest.DISPOSITION, "form-data; name=\"%s\""
    );

    /**
     * Temp directory.
     */
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /**
     * RqMtSmart can return correct part length.
     * @throws Exception If some problem inside
     */
    @Test
    public void returnsCorrectPartLength() throws Exception {
        final String post = "POST /post?u=3 HTTP/1.1";
        final int length = 5000;
        final String part = "x-1";
        final String body =
            new Joined(
                RqMtSmartTest.CRLF,
                RqMtSmartTest.BODY_ELEMENT,
                String.format(RqMtSmartTest.CONTENT, part),
                "",
                StringUtils.repeat("X", length),
                String.format("%s--", RqMtSmartTest.BODY_ELEMENT)
            ).asString();
        final Request req = new RqFake(
            Arrays.asList(
                post,
                "Host: www.example.com",
                RqMtSmartTest.contentLengthHeader(
                    (long) body.getBytes().length
                ),
                RqMtSmartTest.CONTENT_TYPE
            ),
            body
        );
        final RqMtSmart regsmart = new RqMtSmart(
            new RqMtBase(req)
        );
        try {
            MatcherAssert.assertThat(
                regsmart.single(part).body().available(),
                Matchers.equalTo(length)
            );
        } finally {
            req.body().close();
            regsmart.part(part).iterator().next().body().close();
        }
    }

    /**
     * RqMtSmart can identify the boundary even if the last content to
     * read before the pattern is an empty line.
     * @throws Exception If some problem inside
     */
    @Test
    public void identifiesBoundary() throws Exception {
        final int length = 9000;
        final String part = "foo-1";
        final String body =
            new Joined(
                RqMtSmartTest.CRLF,
                "----foo",
                String.format(RqMtSmartTest.CONTENT, part),
                "",
                StringUtils.repeat("F", length),
                "",
                "----foo--"
            ).asString();
        final Request req = new RqFake(
            Arrays.asList(
                "POST /post?foo=3 HTTP/1.1",
                "Host: www.foo.com",
                RqMtSmartTest.contentLengthHeader(
                    (long) body.getBytes().length
                ),
                "Content-Type: multipart/form-data; boundary=--foo"
            ),
            body
        );
        final RqMtSmart regsmart = new RqMtSmart(
            new RqMtBase(req)
        );
        try {
            MatcherAssert.assertThat(
                regsmart.single(part).body().available(),
                Matchers.equalTo(length + RqMtSmartTest.CRLF.length())
            );
        } finally {
            req.body().close();
            regsmart.part(part).iterator().next().body().close();
        }
    }

    /**
     * RqMtSmart can work in integration mode.
     * @throws Exception if some problem inside
     */
    @Test
    public void consumesHttpRequest() throws Exception {
        final String part = "f-1";
        final Take take = new Take() {
            @Override
            public Response act(final Request req) throws Exception {
                return new RsText(
                    new RqPrint(
                        new RqMtSmart(
                            new RqMtBase(req)
                        ).single(part)
                    ).printBody()
                );
            }
        };
        final String body =
            new Joined(
                RqMtSmartTest.CRLF,
                "--AaB0zz",
                String.format(RqMtSmartTest.CONTENT, part), "",
                "my picture", "--AaB0zz--"
            ).asString();
        new FtRemote(take).exec(
            // @checkstyle AnonInnerLengthCheck (50 lines)
            new FtRemote.Script() {
                @Override
                public void exec(final URI home) throws IOException {
                    new JdkRequest(home)
                        .method("POST")
                        .header(
                            "Content-Type",
                            "multipart/form-data; boundary=AaB0zz"
                        )
                        .header(
                            "Content-Length",
                            String.valueOf(body.getBytes().length)
                        )
                        .body()
                        .set(body)
                        .back()
                        .fetch()
                        .as(RestResponse.class)
                        .assertStatus(HttpURLConnection.HTTP_OK)
                        .assertBody(Matchers.containsString("pic"));
                }
            }
        );
    }

    /**
     * RqMtSmart can handle a big request in an acceptable time.
     * @throws Exception If some problem inside
     */
    @Test
    @Category(PerformanceTests.class)
    public void handlesRequestInTime() throws Exception {
        final int length = 100_000_000;
        final String part = "test";
        final File file = this.temp.newFile("handlesRequestInTime.tmp");
        final BufferedWriter bwr = Files.newBufferedWriter(file.toPath());
        bwr.write(
            new Joined(
                RqMtSmartTest.CRLF,
                RqMtSmartTest.BODY_ELEMENT,
                String.format(RqMtSmartTest.CONTENT, part),
                "",
                ""
            ).asString()
        );
        for (int ind = 0; ind < length; ++ind) {
            bwr.write("X");
        }
        bwr.write(RqMtSmartTest.CRLF);
        bwr.write(String.format("%s---", RqMtSmartTest.BODY_ELEMENT));
        bwr.write(RqMtSmartTest.CRLF);
        bwr.close();
        final String post = "POST /post?u=4 HTTP/1.1";
        final long start = System.currentTimeMillis();
        final Request req = new RqFake(
            Arrays.asList(
                post,
                "Host: example.com",
                RqMtSmartTest.CONTENT_TYPE,
                String.format("Content-Length:%s", file.length())
            ),
            new TempInputStream(Files.newInputStream(file.toPath()), file)
        );
        final RqMtSmart smart = new RqMtSmart(
            new RqMtBase(req)
        );
        try {
            MatcherAssert.assertThat(
                smart.single(part).body().available(),
                Matchers.equalTo(length)
            );
            MatcherAssert.assertThat(
                System.currentTimeMillis() - start,
                //@checkstyle MagicNumberCheck (1 line)
                Matchers.lessThan(3000L)
            );
        } finally {
            req.body().close();
            smart.part(part).iterator().next().body().close();
        }
    }

    /**
     * RqMtSmart doesn't distort the content.
     * @throws Exception If some problem inside
     */
    @Test
    public void notDistortContent() throws Exception {
        final int length = 1_000_000;
        final String part = "test1";
        final File file = this.temp.newFile("notDistortContent.tmp");
        final BufferedWriter bwr = Files.newBufferedWriter(file.toPath());
        final String head =
            new Joined(
                RqMtSmartTest.CRLF,
                "--zzz1",
                String.format(RqMtSmartTest.CONTENT, part),
                "",
                ""
            ).asString();
        bwr.write(head);
        final int byt = 0x7f;
        for (int idx = 0; idx < length; ++idx) {
            bwr.write(idx % byt);
        }
        final String foot =
            new Joined(
                RqMtSmartTest.CRLF,
                "",
                "--zzz1--",
                ""
            ).asString();
        bwr.write(foot);
        bwr.close();
        final String post = "POST /post?u=5 HTTP/1.1";
        final Request req = new RqFake(
            Arrays.asList(
                post,
                "Host: exampl.com",
                RqMtSmartTest.contentLengthHeader(
                    head.getBytes().length + length + foot.getBytes().length
                ),
                "Content-Type: multipart/form-data; boundary=zzz1"
            ),
            new TempInputStream(Files.newInputStream(file.toPath()), file)
        );
        final InputStream stream = new RqMtSmart(
            new RqMtBase(req)
        ).single(part).body();
        try {
            MatcherAssert.assertThat(
                stream.available(),
                Matchers.equalTo(length)
            );
            for (int idx = 0; idx < length; ++idx) {
                MatcherAssert.assertThat(
                    String.format("byte %d not matched", idx),
                    stream.read(),
                    Matchers.equalTo(idx % byt)
                );
            }
        } finally {
            req.body().close();
            stream.close();
        }
    }

    /**
     * Format Content-Length header.
     * @param length Body length
     * @return Content-Length header
     */
    private static String contentLengthHeader(final long length) {
        return String.format("Content-Length: %d", length);
    }
}
