package org.prebid.server.bidder.eplanning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.User;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.response.Bid;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.utils.URIBuilder;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.eplanning.model.CleanStepName;
import org.prebid.server.bidder.eplanning.model.HbResponse;
import org.prebid.server.bidder.eplanning.model.HbResponseAd;
import org.prebid.server.bidder.eplanning.model.HbResponseSpace;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpCall;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.eplanning.ExtImpEplanning;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.HttpUtil;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Eplanning {@link Bidder} implementation.
 */
public class EplanningBidder implements Bidder<Void> {

    private static final Integer CUSTOM_MARFEEL_FIXED_WIDTH = 300;
    private static final Integer CUSTOM_MARFEEL_FIXED_HEIGHT = 250;
    private static final String DEFAULT_PAGE_URL = "FILE";
    private static final String SEC = "ROS";
    private static final String DFP_CLIENT_ID = "1";
    private static final String REQUEST_TARGET_INVENTORY = "1";
    private static final List<CleanStepName> CLEAN_STEP_NAMES = Arrays.asList(
            CleanStepName.of("_|\\.|-|\\/", ""),
            CleanStepName.of("\\)\\(|\\(|\\)|:", "_"),
            CleanStepName.of("^_+|_+$", ""));

    private static final TypeReference<ExtPrebid<?, ExtImpEplanning>> EPLANNING_EXT_TYPE_REFERENCE =
            new TypeReference<ExtPrebid<?, ExtImpEplanning>>() {
            };

    private final String endpointUrl;
    private final JacksonMapper mapper;

    public EplanningBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<Void>>> makeHttpRequests(BidRequest request) {
        final List<BidderError> errors = new ArrayList<>();
        final List<String> requestsStrings = new ArrayList<>();

        String clientId = null;
        for (final Imp imp : request.getImp()) {
            try {
                validateImp(imp);
                final ExtImpEplanning extImpEplanning = validateAndModifyImpExt(imp);

                if (clientId == null) {
                    clientId = extImpEplanning.getClientId();
                }
                final String sizeString = resolveSizeString(imp);
                final String name = getCleanAdUnitCode(extImpEplanning, () -> sizeString);
                requestsStrings.add(name + ":" + sizeString);
            } catch (PreBidException e) {
                errors.add(BidderError.badInput(e.getMessage()));
            }
        }

        if (CollectionUtils.isEmpty(requestsStrings)) {
            return Result.withErrors(errors);
        }

        final MultiMap headers = createHeaders(request.getDevice());
        final String uri = resolveRequestUri(request, requestsStrings, clientId);

        return Result.of(Collections.singletonList(
                HttpRequest.<Void>builder()
                        .method(HttpMethod.GET)
                        .uri(uri)
                        .body(null)
                        .headers(headers)
                        .payload(null)
                        .build()),
                errors);
    }

    private static void validateImp(Imp imp) {
        if (imp.getBanner() == null) {
            throw new PreBidException(String.format(
                    "EPlanning only supports banner Imps. Ignoring Imp ID=%s", imp.getId()));
        }
    }

    private ExtImpEplanning validateAndModifyImpExt(Imp imp) throws PreBidException {
        final ExtImpEplanning extImpEplanning;
        try {
            extImpEplanning = mapper.mapper().convertValue(imp.getExt(), EPLANNING_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException ex) {
            throw new PreBidException(String.format(
                    "Ignoring imp id=%s, error while decoding extImpBidder, err: %s", imp.getId(), ex.getMessage()));
        }

        if (extImpEplanning == null) {
            throw new PreBidException(String.format(
                    "Ignoring imp id=%s, error while decoding extImpBidder, err: bidder property is not present",
                    imp.getId()));
        }

        if (StringUtils.isBlank(extImpEplanning.getClientId())) {
            throw new PreBidException(String.format("Ignoring imp id=%s, no ClientID present", imp.getId()));
        }

        return extImpEplanning;
    }

    private static Boolean isMarfeelCustomSize(Integer width, Integer height) {
        return CUSTOM_MARFEEL_FIXED_WIDTH.equals(width) && CUSTOM_MARFEEL_FIXED_HEIGHT.equals(height);
    }

    private static String resolveSizeString(Imp imp) {
        final Banner banner = imp.getBanner();
        final String customMarfeelSize = String.format(
                    "%dx%d",
                    CUSTOM_MARFEEL_FIXED_WIDTH, CUSTOM_MARFEEL_FIXED_HEIGHT);
        final Integer bannerWidth = banner.getW();
        final Integer bannerHeight = banner.getH();

        if (isMarfeelCustomSize(bannerWidth, bannerHeight)) {
            return customMarfeelSize;
        }

        final List<Format> bannerFormats = banner.getFormat();
        if (CollectionUtils.isNotEmpty(bannerFormats)) {
            for (Format format : bannerFormats) {
                final Integer formatHeight = format.getH();
                final Integer formatWidth = format.getW();

                if (isMarfeelCustomSize(formatWidth, formatHeight)) {
                    return customMarfeelSize;
                }
            }
        }

        throw new PreBidException(String.format(
                    "Ignoring imp id=%s, Eplanning doesn't support requested size(s)",
                    imp.getId()));
    }

    private static String cleanName(String name) {
        String result = name;
        for (CleanStepName cleanStepName : CLEAN_STEP_NAMES) {
            result = result.replaceAll(cleanStepName.getExpression(), cleanStepName.getReplacementString());
        }
        return result;
    }

    /**
     * Crates http headers from {@link Device} properties.
     */
    private static MultiMap createHeaders(Device device) {
        final MultiMap headers = HttpUtil.headers();

        if (device != null) {
            HttpUtil.addHeaderIfValueIsNotEmpty(headers, HttpUtil.USER_AGENT_HEADER, device.getUa());
            HttpUtil.addHeaderIfValueIsNotEmpty(headers, HttpUtil.ACCEPT_LANGUAGE_HEADER, device.getLanguage());
            HttpUtil.addHeaderIfValueIsNotEmpty(headers, HttpUtil.X_FORWARDED_FOR_HEADER, device.getIp());
            HttpUtil.addHeaderIfValueIsNotEmpty(headers, HttpUtil.DNT_HEADER, Objects.toString(device.getDnt(), null));
        }

        return headers;
    }

    private String resolveRequestUri(BidRequest request, List<String> requestsStrings, String clientId) {
        final Site site = request.getSite();
        String pageUrl = DEFAULT_PAGE_URL;
        if (site != null && StringUtils.isNotBlank(site.getPage())) {
            pageUrl = site.getPage();
        }

        String pageDomain = DEFAULT_PAGE_URL;
        if (site != null) {
            if (StringUtils.isNotBlank(site.getDomain())) {
                pageDomain = site.getDomain();
            } else if (StringUtils.isNotBlank(site.getPage())) {
                pageDomain = parseUrl(site.getPage()).getHost();
            }
        }

        final App app = request.getApp();
        final String requestTarget = app != null && StringUtils.isNotBlank(app.getBundle())
                ? app.getBundle()
                : pageDomain;

        final String uri = endpointUrl + String.format("/%s/%s/%s/%s", clientId, DFP_CLIENT_ID, requestTarget, SEC);

        final URIBuilder uriBuilder = new URIBuilder()
                .setPath(uri)
                .addParameter("r", "pbs")
                .addParameter("ncb", "1");

        if (app == null) {
            uriBuilder.addParameter("ur", pageUrl);
        }
        uriBuilder.addParameter("e", String.join("+", requestsStrings));

        final User user = request.getUser();
        final String buyeruid = user != null ? user.getBuyeruid() : null;
        if (StringUtils.isNotBlank(buyeruid)) {
            uriBuilder.addParameter("uid", buyeruid);
        }

        final Device device = request.getDevice();
        final String ip = device != null ? device.getIp() : null;
        if (StringUtils.isNotBlank(ip)) {
            uriBuilder.addParameter("ip", ip);
        }

        if (app != null) {
            if (StringUtils.isNotBlank(app.getName())) {
                uriBuilder.addParameter("appn", app.getName());
            }
            if (StringUtils.isNotBlank(app.getId())) {
                uriBuilder.addParameter("appid", app.getId());
            }
            if (request.getDevice() != null && StringUtils.isNotBlank(request.getDevice().getIfa())) {
                uriBuilder.addParameter("ifa", request.getDevice().getIfa());
            }
            uriBuilder.addParameter("app", REQUEST_TARGET_INVENTORY);
        }

        return uriBuilder.toString();
    }

    private static URL parseUrl(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new PreBidException(String.format("Invalid url: %s", url), e);
        }
    }

    /**
     * Converts response to {@link List} of {@link BidderBid}s with {@link List} of errors.
     * Handles cases when response status is different to OK 200.
     */
    @Override
    public Result<List<BidderBid>> makeBids(HttpCall<Void> httpCall, BidRequest bidRequest) {
        try {
            final HbResponse hbResponse = mapper.decodeValue(httpCall.getResponse().getBody(), HbResponse.class);
            return extractBids(hbResponse, bidRequest);
        } catch (DecodeException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private Result<List<BidderBid>> extractBids(HbResponse hbResponse, BidRequest bidRequest) {
        final Map<String, String> nameSpaceToImpId = new HashMap<>();
        for (Imp imp : bidRequest.getImp()) {
            final ExtImpEplanning impExt;
            try {
                impExt = validateAndModifyImpExt(imp);
            } catch (PreBidException e) {
                continue;
            }
            final String name = getCleanAdUnitCode(impExt, () -> resolveSizeString(imp));
            nameSpaceToImpId.put(name, imp.getId());
        }

        return Result.of(getSpacesStream(hbResponse)
                        .flatMap(hbResponseSpace -> getAdsStream(hbResponseSpace)
                                .map(hbResponseAd -> mapToBidderBid(hbResponseSpace, hbResponseAd, nameSpaceToImpId)))
                        .collect(Collectors.toList()),
                Collections.emptyList());
    }

    private static String getCleanAdUnitCode(ExtImpEplanning extImpEplanning, Supplier<String> fallbackNameSupplier) {
        final String adunitCode = extImpEplanning.getAdunitCode();
        return cleanName(StringUtils.isBlank(adunitCode) ? fallbackNameSupplier.get() : adunitCode);
    }

    private static Stream<HbResponseSpace> getSpacesStream(HbResponse hbResponse) {
        return hbResponse.getSpaces() != null ? hbResponse.getSpaces().stream() : Stream.empty();
    }

    private static Stream<HbResponseAd> getAdsStream(HbResponseSpace hbResponseSpace) {
        return hbResponseSpace.getAds() != null ? hbResponseSpace.getAds().stream() : Stream.empty();
    }

    private static BidderBid mapToBidderBid(HbResponseSpace hbResponseSpace, HbResponseAd hbResponseAd,
                                            Map<String, String> nameSpaceToImpId) {
        return BidderBid.of(Bid.builder()
                        .id(hbResponseAd.getImpressionId())
                        .adid(hbResponseAd.getAdId())
                        .impid(nameSpaceToImpId.get(hbResponseSpace.getName()))
                        .price(new BigDecimal(hbResponseAd.getPrice()))
                        .adm(hbResponseAd.getAdM())
                        .crid(hbResponseAd.getCrId())
                        .w(hbResponseAd.getWidth())
                        .h(hbResponseAd.getHeight())
                        .build(),
                BidType.banner, null);
    }
}
