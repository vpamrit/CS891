package edu.vanderbilt.imagecrawler.crawlers.framework;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import edu.vanderbilt.imagecrawler.crawlers.CompletableFuturesCrawler1;
import edu.vanderbilt.imagecrawler.crawlers.SequentialLoopsCrawler;
import edu.vanderbilt.imagecrawler.platform.Cache;
import edu.vanderbilt.imagecrawler.platform.Controller;
import edu.vanderbilt.imagecrawler.platform.PlatformImage;
import edu.vanderbilt.imagecrawler.transforms.Transform;
import edu.vanderbilt.imagecrawler.transforms.TransformDecoratorWithImage;
import edu.vanderbilt.imagecrawler.utils.Array;
import edu.vanderbilt.imagecrawler.utils.ArrayCollector;
import edu.vanderbilt.imagecrawler.utils.BlockingTask;
import edu.vanderbilt.imagecrawler.utils.ConcurrentHashSet;
import edu.vanderbilt.imagecrawler.utils.Crawler;
import edu.vanderbilt.imagecrawler.utils.Image;
import edu.vanderbilt.imagecrawler.utils.WebPageCrawler;

/**
 * This abstract class factors out methods and fields that are common
 * to all image crawler implementation strategies.
 */
public abstract class ImageCrawler
        implements Runnable {
    /**
     * Debugging tag.
     */
    protected final String TAG = this.getClass().getSimpleName();

    /**
     * The List of transforms to applyTransform to the images.
     */
    protected List<Transform> mTransforms;

    /**
     * A cache of unique URIs that have already been processed.
     */
    protected ConcurrentHashSet<String> mUniqueUris;

    /**
     * A web page crawler that parses web pages.
     */
    protected WebPageCrawler mWebPageCrawler;

    /**
     * The maximum crawl depth (from options).
     */
    protected int mMaxDepth;

    /**
     * The root URL or pathname to start the search (from options).
     */
    private String mRootUri;

    /**
     * The cache were images are stored after they have been
     * downloaded and undergone all transformations.
     */
    private Cache mImageCache;

    /**
     * A Function lambda provided by the controller to create
     * a new platform dependent image object.
     */
    private BiFunction<InputStream, Cache.Item, PlatformImage> mNewImageFunction;

    /**
     * A function lambda that maps a uri to a platform dependant
     * input stream.
     */
    private Function<String, InputStream> mMapUriToInputStream;

    /**
     * Keeps track of how long a given test has run and is also
     * used to check if the crawler is currently running.
     */
    private long mStartTime;

    /**
     * Keeps track of all the execution times.
     */
    private List<Long> mExecutionTimes = new ArrayList<>();

    /**
     * Flag used to stop/cancel a crawl.
     */
    private static volatile boolean mCancelled;

    /**
     * Controller instance saved for calling log method.
     */
    private Controller mController;

    /**
     * Constructor that is only available to inner Factory class to
     * support construction using newInstance().
     */
    protected ImageCrawler() {
    }

    /**
     * Called from Factory after creating a new crawler instance and
     * therefore is declared as "package private". Transfers required
     * values from platform Controller to local fields to show
     * explicitly what this and sub-classes require from the
     * controller instance.
     */
    void initialize(Controller controller) {
        // A function lambda that will map a uri to a platform
        // dependant input stream.
        mMapUriToInputStream = controller::mapUriToInputStream;

        // Store the transformations to applyTransform to each
        // downloaded image.
        mTransforms = controller.transforms;

        // Store the root Uri provided by the controller.
        mRootUri = controller.options.rootUrl;

        // The maximum depth for this crawl.
        mMaxDepth = controller.options.maxDepth;

        // A Function lambda the constructs a new platform
        // dependant image.
        mNewImageFunction = controller::newImage;

        // Setup a new WebPageCrawler passing it the platform
        // dependant url to input stream mapping function (used for
        // access local web pages in app resources or assets).
        mWebPageCrawler =
                new WebPageCrawler(controller::mapUriToInputStream);

        // Use the cache implementation provided by the application's
        // controller.
        mImageCache = controller.getCache();

        // Initialize the cache of processed Uris.
        mUniqueUris = new ConcurrentHashSet<>();

        // Save controller for calling log method.
        mController = controller;
    }

    /**
     * A hook method (also a template method) that does bookkeeping
     * operations and dispatches the subclass's performCrawl() hook
     * method to start implementation strategy processing.
     */
    @Override
    public void run() {
        long totalImages = 0;

        try {
            log("Running crawler ...");

            if (mStartTime != 0) {
                throw new IllegalStateException("The crawler is already " +
                        "running.");
            }

            if (mTransforms == null) {
                throw new IllegalStateException("Initialize() must be called " +
                        "before run().");
            }

            // Start timing the test run.
            startTiming();

            // Perform the web crawling starting at the root Uri, given an
            // initial depth count of 1.
            totalImages = performCrawl(mRootUri, 1);

            // Stop timing the test run.
            stopTiming();

            if (isCancelled()) {
                throw new CancellationException("Synthetic CancellationException.");
            }

            log("Crawl completed normally.");
        } catch (Exception e) {
            e.printStackTrace();
            if (e.getCause() instanceof CancellationException) {
                log("Crawl was cancelled.");
            } else {
                System.err.println("Crawl was abnormally terminated: "
                        + e.getMessage());
            }

            throw e;
        } finally {
            log("downloaded and processed "
                    + totalImages
                    + " total image(s)");

            // Always reset the start time to 0 so that it can be
            // used as a flag to see if the crawler is currently
            // running. This allows a single crawler instance to
            // be reusable avoiding unnecessary memory allocations.
            mStartTime = 0;

            // Reset cancelled flag
            mCancelled = false;
        }
    }

    /**
     * Sets a flag that is periodically check at strategic
     * locations to determine if all processing should be
     * cancelled.
     */
    public void stopCrawl() {
        mCancelled = true;
    }

    /**
     * Abstract method required to be implemented by any sub class
     * implementation.
     *
     * @param pageUri The URI that we're crawling at this point
     * @param depth   The current depth of the recursive processing
     * @return A future to the number of images processed
     */
    protected abstract int performCrawl(String pageUri, int depth);

    /**
     * Return an array of all the IMG SRC URLs in this document.
     */
    protected Array<URL> getImagesOnPage(Crawler.Page page) {
        log("Getting images on page ...");

        // Return an array of all the IMG SRC URLs in this page.
        return page
                // Select all the image elements in the page.
                .getPageElementsAsUrls(Crawler.Type.IMAGE)

                // Convert the elements to a stream.
                .stream()

                // Remove duplicate image URL strings.
                .distinct()

                // Trigger intermediate operations and return an array.
                .collect(ArrayCollector.toArray());
    }

    /**
     * Factory method that retrieves the image associated with the @a
     * url and creates an Image to encapsulate it.
     */
    private Image downloadImage(URL url) {
        // Before downloading the next image, check for cancellation
        // and throw and exception if cancelled.
        throwExceptionIfCancelled();

        log("Downloading image ", url);

        // Creates an InputStream from the inputUrl from which to read
        // the image data. The input stream is platform dependant, so
        // we call the controller to provide the platform dependant
        // mapping of the url to an input stream.
        try (InputStream inputStream =
                     mMapUriToInputStream.apply(url.toString())) {

            // Get the reserved cache item.
            Cache.Item item = mImageCache.getItem(url.toString(), null);
            if (item == null) {
                throw new IOException("Item " + url + " not found in cache.");
            }

            // Call platform dependant lambda image creating function to
            // create a new platform image from the input stream.
            Image image = new Image(url,
                    mNewImageFunction.apply(inputStream, item));

            // Save the image into the cache.
            try (OutputStream outputStream =
                         item.getOutputStream(
                                 Cache.Operation.WRITE,
                                 image.size())) {
                image.writeImage(outputStream);
            }

            return image;
        } catch (IOException e) {
            // "Try-with-resources" will clean up the istream
            // automatically.

            System.out.println("Error downloading url " + url + ": " + e);
            e.printStackTrace();

            // Return null so that the crawler can continue on to
            // process other images, hopefully with more luck than
            // this one!
            return null;
        }
    }

    /**
     * Convert URL to an Image by downloading each image via its URL.
     * This call ensures the common fork/join thread pool is expanded
     * to handle the blocking image download.
     */
    private Image blockingDownload(URL url) {
        log("Performing blockingDownload: %s", url);

        return BlockingTask.callInManagedBlock(() -> downloadImage(url));
    }

    /**
     * Factory method that makes a new {@code TransformDecoratorWithImage}.
     */
    protected TransformDecoratorWithImage
    makeTransformDecoratorWithImage(Transform transform,
                                    Image image) {
        return new TransformDecoratorWithImage(transform, image);
    }

    /**
     * Apply the transform to the {@code image}.
     *
     * @param image A downloaded image.
     * @return A a transformed image.
     */
    protected Image applyTransform(Transform transform,
                                   Image image) {
        log("Applying transform to image: %s", image.getSourceUrl());

        // This method will only be called if a new empty cache entry
        // was created by a check to createNewCacheItem. Get that entry and
        // pass it to the CachingTransformerDecorator.
        Cache.Item item = mImageCache.getItem(
                image.getSourceUrl().toString(), transform.getName());

        return makeTransformDecoratorWithImage(transform, image).run(item);
    }

    /**
     * Attempts to add a new cache item for this image transform using
     * the original image and transform group id as a lookup key. If
     * the cache doesn't already contain a matching item, addItem will
     * atomically create a cache entry along with a new file based a
     * the key constructed url/transform id pair and will return true
     * (was added). Otherwise, addItem will return false.
     *
     * @return true if the {@code transform} version of image was
     * added to the cache and false if it was not added (already
     * in cache).
     */
    protected boolean createNewCacheItem(Image image,
                                         Transform transform) {
        log("Attempting to add a cache item for transform: %s",
                image.getSourceUrl());

        // Cache expects a group id string or null which defaults to "raw".
        String groupId = transform != null ? transform.getName() : null;

        // Try to add a new item for this image to the cache. If
        // a matching item does not exist and a new item was added
        // to the cache, this call will return true, otherwise false
        // is returned indicating that the add operation was not
        // performed.
        boolean wasAdded =
            mImageCache.addItem(image.getSourceUrl().toString(),
                                    groupId);

        log("Transform [" + image.getSourceUrl() + "|" + groupId + "]" +
                (wasAdded ? "was added to the cache."
                        : "was already in the cache."));

        // Return whether or an image item was added to the cache.
        return wasAdded;
    }

    /**
     * Attempts to add a new cache item for this {@code url} and
     * {@code cacheGroupId} pair. If the cache doesn't already
     * contain a matching item, addItem will atomically create
     * a cache entry along with a new file based a key constructed
     * from the {@code url} and {@code cacheGroupId} and will return
     * true (was added). Otherwise, addItem will return false.
     *
     * @return true if the {@code url} already exists in file system, else false.
     */
    private boolean createNewCacheItem(URL url,
                                       String cacheGroupId) {

        log("Checking if URL is cached: %s", url.toString());

        // Try to add a new cache item.
        boolean wasAdded =
                mImageCache.addItem(url.toString(),
                                        cacheGroupId);

        log("URL %s was " + (wasAdded ? "not added to the cache"
                        : "already in the cache"), url.toString());

        return wasAdded;
    }

    /**
     * This method first checks the cache for an image that matches the
     * {@code url} and if found returns that; otherwise, it calls the
     * {@code downloadImage} helper method to download the image and then
     * returns the result.
     *
     * @return The url to get from cache or by downloading.
     */
    protected Image getOrDownloadImage(URL url) {
        log("Getting image: %s", url.toString());

        // Attempt to create a new cache item for this image. If a cache item
        // was created (i.e. wasn't already in the cache), then download the
        // image the download and return the image.
        if (createNewCacheItem(url, null)) {
            return blockingDownload(url);
        } else {
            // The image was already cached, so get the cached item.
            Cache.Item item = mImageCache.getItem(url.toString(), null);

            // Does the following:
            // 1. Get the cached item's input stream.
            // 2. Convert the input stream to a byte array.
            // 3. Creates a platform dependant image from the byte array.
            // 4. Decorates the the platform dependant image in an Image object.
            // 5. The try block automatically will close the input stream.
            // 6. Returns the Image decorator object or null if an exception occurred.
            try (InputStream inputStream = item.getInputStream(Cache.Operation.READ)) {
                log("Image %s was already cached, loading image bytes ...", url.toString());
                return new Image(url, mNewImageFunction.apply(inputStream, item));
            } catch (IOException e) {
                log("Download image failed: " + url);
                return null;
            }
        }
    }

    /**
     * @return The controller image cache implementation.
     */
    public Cache getCache() {
        return mImageCache;
    }

    /**
     * Return the time needed to execute the test.
     */
    public List<Long> executionTimes() {
        return mExecutionTimes;
    }

    /**
     * Start timing the test run.
     */
    private void startTiming() {
        // Note the start time.
        mStartTime = System.nanoTime();
    }

    /**
     * Stop timing the test run.
     */
    private void stopTiming() {
        mExecutionTimes.add((System.nanoTime() - mStartTime) / 1_000_000);
    }

    /**
     * Conditionally prints the {@code string} depending on the current
     * setting of the Options singleton.
     */
    protected void log(String string, Object... args) {
        mController.log(getClass().getSimpleName() + ": " + string, args);
    }

    /**
     * @return {@code true} if crawl should be cancelled, {@code false} if not.
     */
    private static boolean isCancelled() {
        return mCancelled;
    }

    /**
     * Throws a CancellationException if the application has
     * decided to cancelled the crawl.
     */
    public static void throwExceptionIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("The crawl has been cancelled.");
        }
    }

    /**
     * @return The supported transforms for this crawler strategy.
     */
    public List<Transform.Type> getSupportedTransforms() {
        return Arrays.asList(Transform.Type.values());
    }

    /**
     * Supported crawlers that can be applied to download, transform,
     * and store images. The enum values are set to class types so
     * that they can be used to create crawler objects using
     * newInstance().
     */
    public enum Type {
        SEQUENTIAL_LOOPS(SequentialLoopsCrawler.class),
        COMPLETABLE_FUTURES1(CompletableFuturesCrawler1.class);

        public final Class<? extends ImageCrawler> clazz;

        Type(Class<? extends ImageCrawler> clazz) {
            this.clazz = clazz;
        }

        @Override
        public String toString() {
            return clazz.getSimpleName();
        }
    }

    /**
     * Java Utility class used to create new instances of supported
     * crawlers.
     */
    public static class Factory {
        /**
         * Disallow object creation.
         */
        private Factory() {
        }

        /**
         * Creates the specified {@code type} transform. Any images
         * downloaded using
         * this transform will be saved in a folder that has the same name as
         * the
         * transform class.
         *
         * @param crawlerType Type of transform.
         * @param controller  A controller that contains all crawler options and
         *                    platform dependent support methods.
         * @return A transform instance of the specified type.
         */
        public static ImageCrawler newCrawler(Type crawlerType,
                                              Controller controller) {
            try {
                ImageCrawler crawler = crawlerType.clazz.newInstance();
                controller.log("Initializing crawler ...");
                crawler.initialize(controller);
                return crawler;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Creates a list of new crawler instances matching the past crawler
         * types.
         *
         * @param crawlerTypes List of crawler types to create.
         * @param controller   A controller that contains all crawler options
         *                     and
         *                     platform dependent support methods.
         * @return A list of new crawler instances.
         */
        public static List<ImageCrawler> newCrawlers(List<Type> crawlerTypes,
                                                     Controller controller) {
            return crawlerTypes
                    .stream()
                    .map(type -> newCrawler(type, controller))
                    .collect(Collectors.toList());
        }
    }
}
