package org.rtb.vexing.bidder;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Publisher;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.Source;
import com.iab.openrtb.request.User;
import com.iab.openrtb.request.Video;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.rtb.vexing.adapter.Adapter;
import org.rtb.vexing.adapter.HttpConnector;
import org.rtb.vexing.bidder.model.BidderBid;
import org.rtb.vexing.bidder.model.BidderSeatBid;
import org.rtb.vexing.execution.GlobalTimeout;
import org.rtb.vexing.model.AdUnitBid;
import org.rtb.vexing.model.Bidder;
import org.rtb.vexing.model.BidderResult;
import org.rtb.vexing.model.MediaType;
import org.rtb.vexing.model.PreBidRequestContext;
import org.rtb.vexing.model.openrtb.ext.response.BidType;
import org.rtb.vexing.model.openrtb.ext.response.ExtHttpCall;
import org.rtb.vexing.model.request.PreBidRequest;
import org.rtb.vexing.model.response.Bid;
import org.rtb.vexing.model.response.BidderDebug;
import org.rtb.vexing.model.response.BidderStatus;

import java.math.BigDecimal;
import java.util.HashSet;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

public class HttpAdapterRequesterTest {
    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private HttpConnector httpConnector;

    @Mock
    private Adapter adapter;

    private HttpAdapterRequester adapterHttpConnector;

    @Before
    public void setUp() {
        // given
        given(adapter.code()).willReturn("someBidderName");
        adapterHttpConnector = new HttpAdapterRequester(adapter, httpConnector);
    }

    @Test
    public void creationShouldFailOnNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> new HttpAdapterRequester(null, null));
        assertThatNullPointerException().isThrownBy(() -> new HttpAdapterRequester(adapter, null));
    }

    @Test
    public void shouldNotSendRequestIfAccountIdIsMissed() {
        // given
        final BidRequest bidRequest = BidRequest.builder().build();

        // when
        final Future<BidderSeatBid> result = adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        verifyZeroInteractions(httpConnector);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(BidderSeatBid.of(emptyList(), null, emptyList(), singletonList(
                "bidrequest.site.publisher.id or bidrequest.app.publisher.id required for legacy bidders.")));
    }

    @Test
    public void shouldNotSendRequestIfAccountIdInSitePublisherIsEmpty() {
        // given
        final BidRequest bidRequest = BidRequest.builder().site(Site.builder()
                .publisher(Publisher.builder().id("").build()).build()).build();

        // when
        final Future<BidderSeatBid> result = adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        verifyZeroInteractions(httpConnector);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(BidderSeatBid.of(emptyList(), null, emptyList(), singletonList(
                "bidrequest.site.publisher.id or bidrequest.app.publisher.id required for legacy bidders.")));
    }

    @Test
    public void shouldNotSendRequestIfTransactionIdIsMissed() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("publisherId").build()).build())
                .build();

        // when
        final Future<BidderSeatBid> result = adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        verifyZeroInteractions(httpConnector);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(BidderSeatBid.of(emptyList(), null, emptyList(), singletonList(
                "bidrequest.source.tid required for legacy bidders.")));
    }

    @Test
    public void shouldNotSendRequestIfTransactionIdIsEmpty() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("publisherId").build()).build())
                .source(Source.builder().tid("").build())
                .build();

        // when
        final Future<BidderSeatBid> result = adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        verifyZeroInteractions(httpConnector);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(BidderSeatBid.of(emptyList(), null, emptyList(), singletonList(
                "bidrequest.source.tid required for legacy bidders.")));
    }

    @Test
    public void shouldNotSendRequestIfSecureFlagMixingInImps() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("publisherId").build()).build())
                .source(Source.builder().tid("TransactionId").build())
                .imp(asList(Imp.builder().secure(0).build(), Imp.builder().secure(1).build()))
                .build();

        // when
        final Future<BidderSeatBid> result = adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        verifyZeroInteractions(httpConnector);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(BidderSeatBid.of(emptyList(), null, emptyList(), singletonList(
                "bidrequest.imp[i].secure must be consistent for legacy bidders. Mixing 0 and 1 are not allowed.")));
    }

    @Test
    public void shouldNotSendRequestIfImpListIsEmpty() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("publisherId").build()).build())
                .source(Source.builder().tid("TransactionId").build())
                .imp(emptyList())
                .tmax(1000L)
                .build();

        // when
        final Future<BidderSeatBid> result = adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        verifyZeroInteractions(httpConnector);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(BidderSeatBid.of(emptyList(), null, emptyList(), singletonList(
                "There no imps in bidRequest for bidder someBidderName")));
    }

    @Test
    public void shouldNotSendRequestIfAllImpsHasInvalidMediaType() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("publisherId").build()).build())
                .source(Source.builder().tid("TransactionId").build())
                .imp(singletonList(Imp.builder().secure(0).build()))
                .tmax(1000L)
                .build();

        // when
        final Future<BidderSeatBid> result = adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        verifyZeroInteractions(httpConnector);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(BidderSeatBid.of(emptyList(), null, emptyList(), singletonList(
                "legacy bidders can only bid on banner and video ad units")));
    }

    @Test
    public void shouldNotSendRequestIfSizesWasNotDefinedInBanner() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("publisherId").build()).build())
                .source(Source.builder().tid("TransactionId").build())
                .imp(singletonList(Imp.builder().secure(0).banner(Banner.builder().topframe(1).build()).build()))
                .tmax(1000L)
                .build();

        // when
        final Future<BidderSeatBid> result = adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        verifyZeroInteractions(httpConnector);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(BidderSeatBid.of(emptyList(), null, emptyList(), singletonList(
                "legacy bidders should have at least one defined size Format")));
    }

    @Test
    public void shouldRespondWithBidAndErrorMessageIfFirstImpIsValidAndSecondIsNot() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("publisherId").build()).build())
                .source(Source.builder().tid("TransactionId").build())
                .imp(asList(Imp.builder().secure(0).build(),
                        Imp.builder().banner(Banner.builder().topframe(1)
                                .format(singletonList(Format.builder().build())).build())
                                .ext((ObjectNode) Json.mapper.createObjectNode().set("bidder",
                                        Json.mapper.createObjectNode()))
                                .build()))
                .tmax(1000L)
                .build();

        given(httpConnector.call(any(), any(), any())).willReturn(Future.succeededFuture(BidderResult.builder()
                .bids(singletonList(Bid.builder().mediaType(MediaType.banner).build()))
                .bidderStatus(BidderStatus.builder().debug(singletonList(BidderDebug.builder().build())).build())
                .build()));

        // when
        final Future<BidderSeatBid> result = adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(BidderSeatBid.of(
                singletonList(BidderBid.of(com.iab.openrtb.response.Bid.builder().build(), BidType.banner)),
                null,
                singletonList(ExtHttpCall.builder().build()),
                singletonList("legacy bidders can only bid on banner and video ad units")));
    }

    @Test
    public void shouldReturnErrorInResponseIfMediaTypeInResponseIsNull() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("publisherId").build()).build())
                .source(Source.builder().tid("TransactionId").build())
                .imp(singletonList(Imp.builder().banner(Banner.builder().topframe(1)
                        .format(singletonList(Format.builder().build())).build())
                        .ext((ObjectNode) Json.mapper.createObjectNode().set("bidder",
                                Json.mapper.createObjectNode())).build()))
                .tmax(1000L)
                .build();

        given(httpConnector.call(any(), any(), any())).willReturn(Future.succeededFuture(BidderResult.builder()
                .bids(singletonList(Bid.builder().build()))
                .bidderStatus(BidderStatus.builder().debug(singletonList(BidderDebug.builder().build())).build())
                .build()));

        // when
        final Future<BidderSeatBid> futureResult = adapterHttpConnector.requestBids(bidRequest,
                GlobalTimeout.create(500));

        // then
        assertThat(futureResult.succeeded()).isTrue();
        assertThat(futureResult.result()).isEqualTo(BidderSeatBid.of(emptyList(), null, singletonList(ExtHttpCall
                .builder().build()), singletonList("Media Type is not defined for Bid")));
    }

    @Test
    public void shouldReturnErrorsFromBothBidderAndResponseCreation() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("publisherId").build()).build())
                .source(Source.builder().tid("TransactionId").build())
                .imp(asList(Imp.builder().secure(0).build(),
                        Imp.builder().banner(Banner.builder().topframe(1)
                                .format(singletonList(Format.builder().build())).build())
                                .ext((ObjectNode) Json.mapper.createObjectNode().set("bidder",
                                        Json.mapper.createObjectNode()))
                                .build()))
                .tmax(1000L)
                .build();

        given(httpConnector.call(any(), any(), any())).willReturn(Future.succeededFuture(BidderResult.builder()
                .bids(singletonList(Bid.builder().build()))
                .bidderStatus(BidderStatus.builder().debug(singletonList(BidderDebug.builder().build())).build())
                .build()));

        // when
        final Future<BidderSeatBid> futureResult = adapterHttpConnector.requestBids(bidRequest,
                GlobalTimeout.create(500));

        // then
        assertThat(futureResult.succeeded()).isTrue();
        assertThat(futureResult.result()).isEqualTo(BidderSeatBid.of(emptyList(), null, singletonList(ExtHttpCall
                .builder().build()), asList("legacy bidders can only bid on banner and video ad units",
                "Media Type is not defined for Bid")));
    }

    @Test
    public void shouldAddAdnxCookieIfUserIdIsNotNull() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("publisherId").build()).build())
                .source(Source.builder().tid("TransactionId").build())
                .imp(singletonList(Imp.builder().secure(0).banner(Banner.builder().topframe(1)
                        .format(singletonList(Format.builder().build())).build())
                        .ext((ObjectNode) Json.mapper.createObjectNode().set("bidder", Json.mapper.createObjectNode()))
                        .build()))
                .user(User.builder().id("someId").build())
                .tmax(1000L)
                .build();

        given(httpConnector.call(any(), any(), any())).willReturn(Future.succeededFuture(BidderResult.builder()
                .bids(singletonList(Bid.builder().mediaType(MediaType.banner).build()))
                .bidderStatus(BidderStatus.builder().debug(singletonList(BidderDebug.builder().build())).build())
                .build()));

        // when
        adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        final PreBidRequestContext preBidRequestContext = capturePreBidRequestContext();
        assertThat(preBidRequestContext.uidsCookie.uidFrom("adnxs")).isEqualTo("someId");
    }

    @Test
    public void shouldTakeAccountIdFromSitePublisherIfSiteIsNotNull() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder().publisher(Publisher.builder().id("sitePublisherId").build()).build())
                .imp(singletonList(Imp.builder().secure(0).banner(Banner.builder().topframe(1)
                        .format(singletonList(Format.builder().build())).build())
                        .ext((ObjectNode) Json.mapper.createObjectNode().set("bidder", Json.mapper.createObjectNode()))
                        .build()))
                .source(Source.builder().tid("TransactionId").build())
                .tmax(1000L)
                .build();

        given(httpConnector.call(any(), any(), any())).willReturn(Future.succeededFuture(BidderResult.builder()
                .bids(singletonList(Bid.builder().mediaType(MediaType.banner).build()))
                .bidderStatus(BidderStatus.builder().debug(singletonList(BidderDebug.builder().build())).build())
                .build()));

        // when
        adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        final PreBidRequestContext preBidRequestContext = capturePreBidRequestContext();
        assertThat(preBidRequestContext.preBidRequest.accountId).isEqualTo("sitePublisherId");
    }

    @Test
    public void shouldTakeAccountIdFromAppPublisherIfSiteIsNullAndAppIsNotNull() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .app(App.builder().publisher(Publisher.builder().id("appPublisherId").build()).build())
                .imp(singletonList(Imp.builder().secure(0).banner(Banner.builder().topframe(1)
                        .format(singletonList(Format.builder().build())).build())
                        .ext((ObjectNode) Json.mapper.createObjectNode().set("bidder", Json.mapper.createObjectNode()))
                        .build()))
                .source(Source.builder().tid("TransactionId").build())
                .tmax(1000L)
                .build();

        given(httpConnector.call(any(), any(), any())).willReturn(Future.succeededFuture(BidderResult.builder()
                .bids(singletonList(Bid.builder().mediaType(MediaType.banner).build()))
                .bidderStatus(BidderStatus.builder().debug(singletonList(BidderDebug.builder().build())).build())
                .build()));

        // when
        adapterHttpConnector.requestBids(bidRequest, GlobalTimeout.create(500));

        // then
        final PreBidRequestContext preBidRequestContext = capturePreBidRequestContext();
        assertThat(preBidRequestContext.preBidRequest.accountId).isEqualTo("appPublisherId");
    }

    @Test
    public void shouldSendRequestWithCorrectlyFilledPreBidRequestContextAndBidder() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .site(Site.builder()
                        .domain("domain").page("page").publisher(Publisher.builder().id("publisherId").build())
                        .build())
                .source(Source.builder().tid("TransactionId").build())
                .imp(singletonList(Imp.builder()
                        .banner(Banner.builder().topframe(1).format(singletonList(
                                Format.builder().h(100).w(200).build())).build())
                        .video(Video.builder()
                                .mimes(singletonList("mime"))
                                .minduration(100)
                                .maxduration(1000)
                                .startdelay(1)
                                .skip(1)
                                .playbackmethod(singletonList(1))
                                .protocols(singletonList(1))
                                .build())
                        .id("impId")
                        .instl(2)
                        .ext((ObjectNode) Json.mapper.createObjectNode().set("bidder", Json.mapper.createObjectNode()))
                        .build()))
                .tmax(100L)
                .test(1)
                .device(Device.builder().ip("ip").build())
                .user(User.builder().buyeruid("buyeruid").build())
                .build();

        given(adapter.cookieFamily()).willReturn("someCookieFamily");
        given(adapter.code()).willReturn("someCode");

        given(httpConnector.call(any(), any(), any())).willReturn(Future.succeededFuture(BidderResult.builder()
                .bids(singletonList(Bid.builder().mediaType(MediaType.banner).build()))
                .bidderStatus(BidderStatus.builder().debug(singletonList(BidderDebug.builder().build())).build())
                .build()));
        GlobalTimeout globalTimeout = GlobalTimeout.create(500);

        // when
        adapterHttpConnector.requestBids(bidRequest, globalTimeout);

        // then
        final PreBidRequestContext preBidRequestContext = capturePreBidRequestContext();
        final org.rtb.vexing.model.Bidder bidder = captureBidder();
        assertThat(preBidRequestContext.preBidRequest).isEqualTo(
                PreBidRequest.builder()
                        .accountId("publisherId")
                        .tid("TransactionId")
                        .secure(0)
                        .timeoutMillis(100L)
                        .device(Device.builder().ip("ip").build())
                        .user(User.builder().buyeruid("buyeruid").build())
                        .build());

        // compare fields separately because uidCookie's expiration time is different in expected and actual objects
        assertThat(preBidRequestContext.referer).isEqualTo("page");
        assertThat(preBidRequestContext.domain).isEqualTo("domain");
        assertThat(preBidRequestContext.timeout).isSameAs(globalTimeout);
        assertThat(preBidRequestContext.uidsCookie.uidFrom("someCookieFamily")).isEqualTo("buyeruid");

        assertThat(bidder).isEqualTo(Bidder.from("someCode", singletonList(AdUnitBid.builder()
                .bidderCode("someCode")
                .bidId("impId")
                .sizes(singletonList(Format.builder().w(200).h(100).build()))
                .topframe(1)
                .instl(2)
                .adUnitCode("impId")
                .video(org.rtb.vexing.model.request.Video.builder()
                        .mimes(singletonList("mime"))
                        .minduration(100)
                        .maxduration(1000)
                        .startdelay(1)
                        .skippable(1)
                        .playbackMethod(1)
                        .protocols(singletonList(1))
                        .build())
                .mediaTypes(new HashSet<>(asList(MediaType.video, MediaType.banner)))
                .params(Json.mapper.createObjectNode())
                .build())));
    }

    @Test
    public void shouldReturnCorrectBidderSeatResponse() {
        // given
        final BidRequest bidRequest = BidRequest.builder()
                .app(App.builder().publisher(Publisher.builder().id("appPublisherId").build()).build())
                .imp(singletonList(Imp.builder().secure(0).banner(Banner.builder().topframe(1)
                        .format(singletonList(Format.builder().build())).build())
                        .ext((ObjectNode) Json.mapper.createObjectNode().set("bidder", Json.mapper.createObjectNode()))
                        .build()))
                .source(Source.builder().tid("TransactionId").build())
                .tmax(1000L)
                .build();

        given(httpConnector.call(any(), any(), any())).willReturn(Future.succeededFuture(BidderResult.builder()
                .bids(singletonList(Bid.builder().mediaType(MediaType.banner).code("code").creativeId("creativeId")
                        .price(BigDecimal.ONE).nurl("nurl").adm("adm").width(100).height(200).dealId("dealId")
                        .build()))
                .bidderStatus(BidderStatus.builder().debug(singletonList(BidderDebug.builder()
                        .requestBody("requestBody")
                        .requestUri("requestUri")
                        .responseBody("responseBody")
                        .statusCode(2)
                        .build()))
                        .build())

                .build()));

        // when
        final Future<BidderSeatBid> futureResult = adapterHttpConnector.requestBids(bidRequest,
                GlobalTimeout.create(500));

        // then
        assertThat(futureResult.result()).isEqualTo(BidderSeatBid.of(singletonList(
                BidderBid.of(com.iab.openrtb.response.Bid.builder()
                        .impid("code").crid("creativeId").price(BigDecimal.ONE).nurl("nurl").adm("adm").w(100).h(200)
                        .dealid("dealId").build(), BidType.banner)), null, singletonList(ExtHttpCall.builder()
                        .responsebody("responseBody").requestbody("requestBody").status(2).uri("requestUri").build()),
                emptyList()));
    }

    private PreBidRequestContext capturePreBidRequestContext() {
        final ArgumentCaptor<PreBidRequestContext> preBidRequestContextArgumentCaptor = ArgumentCaptor
                .forClass(PreBidRequestContext.class);
        verify(httpConnector).call(eq(adapter), any(), preBidRequestContextArgumentCaptor.capture());
        return preBidRequestContextArgumentCaptor.getValue();
    }

    private org.rtb.vexing.model.Bidder captureBidder() {
        final ArgumentCaptor<org.rtb.vexing.model.Bidder> bidderArgumentCaptor =
                ArgumentCaptor.forClass(org.rtb.vexing.model.Bidder.class);
        verify(httpConnector).call(eq(adapter), bidderArgumentCaptor.capture(), any());
        return bidderArgumentCaptor.getValue();
    }
}