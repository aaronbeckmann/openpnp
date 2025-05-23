package org.openpnp.vision.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.openpnp.vision.FluentCv.ColorSpace;
import org.openpnp.vision.pipeline.CvStage.Result;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.convert.AnnotationStrategy;
import org.simpleframework.xml.core.Persister;
import org.simpleframework.xml.stream.Format;
import org.simpleframework.xml.stream.HyphenStyle;
import org.simpleframework.xml.stream.Style;

/**
 * A CvPipeline performs computer vision operations on a working image by processing in series a
 * list of CvStage instances. Each CvStage instance can modify the working image and return a new
 * image along with data extracted from the image. After processing the image callers can get access
 * to the images and models from each stage.
 * 
 * CvPipeline is serializable using toXmlString and fromXmlString. This makes it easy to export
 * pipelines and exchange them with others.
 * 
 * This work takes inspiration from several existing projects:
 * 
 * FireSight by Karl Lew and Šimon Fojtů: https://github.com/firepick1/FireSight
 * 
 * RoboRealm: http://www.roborealm.com/
 * 
 * TODO: Add measuring to image window.
 * 
 * TODO: Add info showing pixel coordinates when mouse is in image window.
 */
@Root
public class CvPipeline implements AutoCloseable {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    @ElementList
    private ArrayList<CvStage> stages = new ArrayList<>();

    private Map<CvStage, Result> results = new HashMap<CvStage, Result>();

    private Map<String, Object> properties = new HashMap<String, Object>();

    private ArrayList<PipelineShot> compositeShots = new ArrayList<>();

    private Mat workingImage;
    private Object workingModel;
    private Exception terminalException;
    private ColorSpace workingColorSpace;

    private long totalProcessingTimeNs;

    private BufferedImage lastCapturedImage;

    private int currentShot;

    public CvPipeline() {
        
    }

    public CvPipeline(String xmlPipeline) {
        try {
            fromXmlString(xmlPipeline);
        }
        catch (Exception e) {
            throw new Error(e);
        }
    }

    /**
     * Add the given CvStage to the end of the pipeline using the given name. If name is null a
     * unique one will be generated and set on the stage.
     * 
     * @param name
     * @param stage
     */
    public void add(String name, CvStage stage) {
        if (name == null) {
            name = generateUniqueName();
        }
        stage.setName(name);
        stages.add(stage);
    }

    /**
     * Add the given CvStage to the end of the pipeline. If the stage does not have a name a unique
     * one will be generated and set on the stage.
     * 
     * @param stage
     */
    public void add(CvStage stage) {
        add(stage.getName(), stage);
    }

    public void insert(String name, CvStage stage, int index) {
        if (name == null) {
            name = generateUniqueName();
        }
        stage.setName(name);
        stages.add(index, stage);
    }

    public void insert(CvStage stage, int index) {
        insert(stage.getName(), stage, index);
    }

    public void remove(String name) {
        remove(getStage(name));
    }

    public void remove(CvStage stage) {
        stages.remove(stage);
    }

    public List<CvStage> getStages() {
        return Collections.unmodifiableList(stages);
    }

    public CvStage getStage(String name) {
        if (name == null) {
            return null;
        }
        for (CvStage stage : stages) {
            if (stage.getName().equals(name)) {
                return stage;
            }
        }
        return null;
    }

    /**
     * @return Active parameter stages used to control select pipeline stage properties. 
     */
    public List<CvAbstractParameterStage> getParameterStages() {
        return stages
                .stream()
                .filter(p -> p.isEnabled() && p instanceof CvAbstractParameterStage)
                .map(p -> (CvAbstractParameterStage) p)
                .filter(p -> p.parameterName() != null)
                .collect(Collectors.toList());
    }

    /**
     * Get the Result returned by the CvStage with the given name. May return null if the stage did
     * not return a result.
     * 
     * @param name
     * @return
     */
    public Result getResult(String name) {
        if (name == null) {
            return null;
        }
        return getResult(getStage(name));
    }

    /**
     * Get the Result returned by the CvStage with the given name, expected to be defined in the pipeline 
     * and to return a non-null model. 
     * 
     * @param name
     * @return
     * @throws Exception when the stage name is undefined or the stage is missing in the pipeline.
     */
    public Result getExpectedResult(String name) throws Exception {
        if (name == null || name.trim().isEmpty()) {
            throw new Exception("Stage name must be given.");
        }
        CvStage stage = getStage(name);
        if (stage == null) {
            throw new Exception("Stage \""+name+"\" is missing in the pipeline.");
        }
        Result result = getResult(stage);
        if (result == null) {
            throw new Exception("Stage \""+name+"\" returned no result.");
        }
        if (result.model instanceof Exception) {
            throw (Exception)(result.model);
        }
        return result;
    }

    /**
     * Get the Result returned by give CvStage. May return null if the stage did not return a
     * result.
     * 
     * @param stage
     * @return
     */
    public Result getResult(CvStage stage) {
        if (stage == null) {
            return null;
        }
        return results.get(stage);
    }

    /**
     * Get the current working image. Primarily intended to be called from CvStage implementations.
     * 
     * @return
     */
    public Mat getWorkingImage() {
        if (workingImage == null || (workingImage.cols() == 0 && workingImage.rows() == 0)) {
            workingImage = new Mat(480, 640, CvType.CV_8UC3, new Scalar(0, 0, 0));
            Imgproc.line(workingImage, new Point(0, 0), new Point(640, 480), new Scalar(0, 0, 255));
            Imgproc.line(workingImage, new Point(640, 0), new Point(0, 480), new Scalar(0, 0, 255));
            workingColorSpace = ColorSpace.Bgr;
        }
        return workingImage;
    }

    public Object getWorkingModel() {
      return workingModel;
    }
    
    public ColorSpace getWorkingColorSpace() {
        return workingColorSpace;
    }
    
    public void setWorkingColorSpace(ColorSpace colorSpace) {
        workingColorSpace = colorSpace;
    }

    Exception getTerminalException() {
        return terminalException;
    }

    void setTerminalException(Exception exception) {
        this.terminalException = exception;
    }

    public long getTotalProcessingTimeNs() {
      return totalProcessingTimeNs;
    }

    public void setTotalProcessingTimeNs(long totalProcessingTimeNs) {
      this.totalProcessingTimeNs = totalProcessingTimeNs;
    }

    public void process() throws Exception {
        terminalException = null;
        totalProcessingTimeNs = 0;
        release();
        for (CvStage stage : stages) {
            stage.processPrepare(this);
        }
        for (CvStage stage : stages) {
            // Process and time the stage and get the result.
            long processingTimeNs = System.nanoTime();
            Result result = null;
            try {
                if (!stage.isEnabled()) {
                    throw new Exception(String.format("Stage \"%s\"not enabled.", stage.getName()));
                }
                result = stage.process(this);
            }
            catch (TerminalException e) {
                result = new Result(null, e.getOriginalException());
                setTerminalException(e.getOriginalException());
                Logger.debug("Stage \""+stage.getName()+"\" throws "+e.getOriginalException());
            }
            catch (Exception e) {
                result = new Result(null, e);
                if (stage.isEnabled()) {
                    Logger.debug("Stage \""+stage.getName()+"\" throws "+e);
                }
            }
            processingTimeNs = System.nanoTime() - processingTimeNs;
            totalProcessingTimeNs += processingTimeNs;

            Mat image = null;
            Object model = null;
            ColorSpace colorSpace = null;
            if (result != null) {
                image = result.image;
                model = result.model;
                colorSpace = result.colorSpace;
            }
            if(stage.isEnabled() && model != null) {
                workingModel = model;
            }
            if(stage.isEnabled() && colorSpace != null) {
                workingColorSpace = colorSpace;
            }
            // If the result image is null and there is a working image,
            // replace the result image with a clone of the working image.
            if (image == null) {
                if (workingImage != null) {
                    image = workingImage.clone();
                }
            }
            // If the result image is not null:
            // Release the working image if the result image is different.
            // Replace the working image with the result image.
            // Clone the result image for storage.
            else {
                if (workingImage != null && workingImage != image) {
                    workingImage.release();
                }
                workingImage = image;
                image = image.clone();
            }

            // If the result colorSpace is null and there is a working colorSpace,
            // replace the result colorSpace with the working colorSpace.
            if (colorSpace == null) {
                if (workingColorSpace != null) {
                    colorSpace = workingColorSpace;
                }
            }

            results.put(stage, new Result(image, colorSpace, model, processingTimeNs, stage));
        }
        if (terminalException != null) {
            throw (terminalException);
        }
    }

    /**
     * Reset all the modified parameters to default values
     * (we do not want the parameters to permanently modify the pipeline). 
     */
    public void resetToDefaults() {
        for (CvAbstractParameterStage stage : getParameterStages()) {
            stage.resetParameterValue(this);
        }
    }

    /**
     * Release any temporary resources associated with the processing of the pipeline. Should be
     * called when the pipeline is no longer needed. This is primarily to release retained native
     * resources from OpenCV.
     */
    public void release() {
        if (workingImage != null) {
            workingImage.release();
            workingImage = null;
        }
        for (Result result : results.values()) {
            if (result.image != null) {
                result.image.release();
            }
        }
        workingModel = null;
        results.clear();
    }
    
    @Override
    public void close() throws IOException {
        release();
    }
    
    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    /**
     * Convert the pipeline to an XML string that can be read back in with #fromXmlString.
     * 
     * @return
     * @throws Exception
     */
    public String toXmlString() throws Exception {
        resetToDefaults();
        Serializer ser = createSerializer();
        StringWriter sw = new StringWriter();
        ser.write(this, sw);
        return sw.toString();
    }

    /**
     * Parse the pipeline in the given String and replace the current pipeline with the results.
     * 
     * @param s
     * @throws Exception
     */
    public void fromXmlString(String s) throws Exception {
        release();
        Serializer ser = createSerializer();
        StringReader sr = new StringReader(s);
        CvPipeline pipeline = ser.read(CvPipeline.class, sr);
        stages.clear();
        for (CvStage stage : pipeline.getStages()) {
            add(stage);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        else if (other instanceof CvPipeline) {
            try {
                return toXmlString().equals(((CvPipeline) other).toXmlString());
            }
            catch (Exception e) {
                //ignore
            }
        }
        return false;
    }

    private String generateUniqueName() {
        for (int i = 0;; i++) {
            String name = "" + i;
            if (getStage(name) == null) {
                return name;
            }
        }
    }

    @Override
    public CvPipeline clone() throws CloneNotSupportedException {
        try {
            return new CvPipeline(toXmlString());
        }
        catch (Exception e) {
            throw new CloneNotSupportedException(e.getMessage());
        }
    }
    
    public Object getProperty(String name) {
        return properties.get(name);
    }
    
    public void setProperty(String name, Object value) {
        properties.put(name, value);
    }

    public void addProperties(Map<String, Object> pipelineParameterAssignments) {
        if (pipelineParameterAssignments != null) {
            properties.putAll(pipelineParameterAssignments);
        }
    }

    public void resetReusedPipeline() {
        properties = new HashMap<>();
        compositeShots = new ArrayList<>();
    }

    private static Serializer createSerializer() {
        Style style = new HyphenStyle();
        Format format = new Format(style);
        AnnotationStrategy strategy = new AnnotationStrategy();
        Serializer serializer = new Persister(strategy, format);
        return serializer;
    }

    public BufferedImage getLastCapturedImage() {
        return lastCapturedImage;
    }

    public void setLastCapturedImage(BufferedImage lastCapturedImage) {
        this.lastCapturedImage = lastCapturedImage;
    }

    public abstract class PipelineShot {
        private Map<String, Object> properties;
        private final int index; 

        /**
         * Records the customized pipeline properties for this shot.
         * 
         * Use a derived class to add user defined data and methods
         * to the PipelineShot.
         */
        public PipelineShot() {
            super();
            // Record a snapshot of the current properties
            properties = new HashMap<>();
            properties.putAll(CvPipeline.this.properties);
            index = compositeShots.size(); 
            CvPipeline.this.compositeShots.add(this);
            CvPipeline.this.currentShot = index; 
        }

        public int getIndex() {
            return index;
        }

        /**
         * Apply the recorded pipeline properties for this shot to the pipeline.
         * Override this method to define custom actions, such as moving the 
         * subject/camera to the specific shot location. 
         * 
         * @throws Exception
         */
        public void apply() throws Exception {
            CvPipeline.this.properties = new HashMap<>();
            CvPipeline.this.properties.putAll(properties);
            CvPipeline.this.currentShot = index;
        }

        /**
         * Process the result of one shot. 
         * Override this method to define the custom operations.
         * 
         * @param result
         */
        public abstract void processResult(Result result);

        /**
         * Process the composite result of all the shots. 
         * Override this method to define custom operations.
         */
        public abstract Result processCompositeResult() throws Exception;
    }

    public List<PipelineShot> getPipelineShots() {
        return Collections.unmodifiableList(compositeShots);
    }

    public PipelineShot getCurrentPipelineShot() {
        return getPipelineShot(currentShot);
    }

    public PipelineShot getPipelineShot(int i) {
        if (i >= 0 && i < compositeShots.size()) {
            return compositeShots.get(i);
        }
        return null;
    }

    public int getPipelineShotsCount() {
        return compositeShots.size();
    }

    public void stepToNextPipelineShot() throws Exception {
        if (compositeShots.size() > 1) {
            int i = (currentShot + 1) % compositeShots.size();
            getPipelineShot(i).apply();
        }
    }
}
