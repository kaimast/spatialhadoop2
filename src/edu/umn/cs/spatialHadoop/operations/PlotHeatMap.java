/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the
 * NOTICE file distributed with this work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package edu.umn.cs.spatialHadoop.operations;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.IndexedSortable;
import org.apache.hadoop.util.QuickSort;

import edu.umn.cs.spatialHadoop.ImageOutputFormat;
import edu.umn.cs.spatialHadoop.ImageWritable;
import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.Point;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.Shape;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.mapred.BlockFilter;
import edu.umn.cs.spatialHadoop.mapred.ShapeArrayInputFormat;
import edu.umn.cs.spatialHadoop.mapred.ShapeInputFormat;
import edu.umn.cs.spatialHadoop.mapred.ShapeRecordReader;
import edu.umn.cs.spatialHadoop.mapred.TextOutputFormat;
import edu.umn.cs.spatialHadoop.nasa.NASAPoint;
import edu.umn.cs.spatialHadoop.nasa.NASARectangle;
import edu.umn.cs.spatialHadoop.operations.Aggregate.MinMax;
import edu.umn.cs.spatialHadoop.operations.RangeQuery.RangeFilter;

/**
 * Draws an image of all shapes in an input file.
 * @author Ahmed Eldawy
 *
 */
public class PlotHeatMap {
  /**Logger*/
  private static final Log LOG = LogFactory.getLog(PlotHeatMap.class);
  
  public static class FrequencyMap implements Writable {
    int[][] frequency;
    private MinMax valueRange;
    
    private Map<Integer, BufferedImage> cachedCircles = new HashMap<Integer, BufferedImage>();
    
    public FrequencyMap() {
    }
    
    public FrequencyMap(int width, int height) {
      frequency = new int[width][height];
    }
    
    public FrequencyMap(FrequencyMap other) {
      this.frequency = new int[other.getWidth()][other.getHeight()];
      for (int x = 0; x < this.getWidth(); x++)
        for (int y = 0; y < this.getHeight(); y++) {
          this.frequency[x][y] = other.frequency[x][y];
        }
    }

    public void combine(FrequencyMap other) {
      if (other.getWidth() != this.getWidth() ||
          other.getHeight() != this.getHeight())
        throw new RuntimeException("Incompatible frequency map sizes "+this+", "+other);
      for (int x = 0; x < this.getWidth(); x++)
        for (int y = 0; y < this.getHeight(); y++) {
          this.frequency[x][y] += other.frequency[x][y];
        }
    }

    @Override
    public void write(DataOutput out) throws IOException {
      out.writeInt(this.getWidth());
      out.writeInt(this.getHeight());
      for (int[] col : frequency) {
        for (int value : col) {
          out.writeInt(value);
        }
      }
    }
    
    @Override
    public void readFields(DataInput in) throws IOException {
      int width = in.readInt();
      int height = in.readInt();
      frequency = new int[width][height];
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          frequency[x][y] = in.readInt();
        }
      }
    }
    
    @Override
    protected FrequencyMap clone() {
      return new FrequencyMap(this);
    }
    
    @Override
    public String toString() {
      return "Frequency Map:"+this.getWidth()+"x"+this.getHeight();
    }
    
    private MinMax getValueRange() {
      Map<Integer, Integer> histogram = new HashMap<Integer, Integer>();
      MinMax minMax = new MinMax(Integer.MAX_VALUE, Integer.MIN_VALUE);
      for (int[] col : frequency)
        for (int value : col) {
          if (!histogram.containsKey(value)) {
            histogram.put(value, 1);
          } else {
            histogram.put(value, histogram.get(value) + 1);
          }
          minMax.expand(value);
        }
      final int[] keys = new int[histogram.size()];
      final int[] values = new int[histogram.size()];
      int i = 0;
      for (Map.Entry<Integer, Integer> entry : histogram.entrySet()) {
        keys[i] = entry.getKey();
        values[i] = entry.getValue();
        i++;
      }
      new QuickSort().sort(new IndexedSortable() {
        @Override
        public int compare(int i, int j) {
          return keys[i] - keys[j];
        }

        @Override
        public void swap(int i, int j) {
          int t = keys[i];
          keys[i] = keys[j];
          keys[j] = t;
          
          t = values[i];
          values[i] = values[j];
          values[j] = t;
        }
        
      }, 0, keys.length);
      for (i = 0; i < keys.length; i++)
        System.out.println(keys[i]+","+values[i]);
      return minMax;
    }

    private int getWidth() {
      return frequency.length;
    }

    private int getHeight() {
      return frequency[0].length;
    }

    public BufferedImage toImage(MinMax valueRange, boolean skipZeros) {
      if (valueRange == null)
        valueRange = getValueRange();
      LOG.info("Using the value range: "+valueRange);
      NASAPoint.minValue = valueRange.minValue;
      NASAPoint.maxValue = valueRange.maxValue;
      BufferedImage image = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
      for (int x = 0; x < this.getWidth(); x++)
        for (int y = 0; y < this.getHeight(); y++) {
          if (!skipZeros || frequency[x][y] > 0) {
            Color color = NASARectangle.calculateColor(frequency[x][y]);
            image.setRGB(x, y, color.getRGB());
          }
        }
      return image;
    }

    public void addPoint(int cx, int cy, int radius) {
      BufferedImage circle = getCircle(radius);
      for (int x = 0; x < circle.getWidth(); x++) {
        for (int y = 0; y < circle.getHeight(); y++) {
          int imgx = x - radius + cx;
          int imgy = y - radius + cy;
          if (imgx >= 0 && imgx < getWidth() && imgy >= 0 && imgy < getHeight()) {
            boolean filled = (circle.getRGB(x, y) & 0xff) == 0;
            if (filled) {
              frequency[x - radius + cx][y - radius + cy]++;
            }
          }
        }
      }
    }

    public BufferedImage getCircle(int radius) {
      BufferedImage circle = cachedCircles.get(radius);
      if (circle == null) {
        circle = new BufferedImage(radius * 2, radius * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = circle.createGraphics();
        graphics.setBackground(Color.WHITE);
        graphics.clearRect(0, 0, radius * 2, radius * 2);
        graphics.setColor(Color.BLACK);
        graphics.fillArc(0, 0, radius * 2, radius * 2, 0, 360);
        graphics.dispose();
        cachedCircles.put(radius, circle);
      }
      return circle;
    }
  }

  /**
   * If the processed block is already partitioned (via global index), then
   * the output is the same as input (Identity map function). If the input
   * partition is not partitioned (a heap file), then the given shape is output
   * to all overlapping partitions.
   * @author Ahmed Eldawy
   *
   */
  public static class PlotHeatMapMap extends MapReduceBase 
    implements Mapper<Rectangle, ArrayWritable, NullWritable, FrequencyMap> {

    /**Only objects inside this query range are drawn*/
    private Shape queryRange;
    private int imageWidth;
    private int imageHeight;
    private Rectangle drawMbr;
    /**Used to output values*/
    private FrequencyMap frequencyMap;
    /**Radius to use for smoothing the heat map*/
    private int radius;
    
    @Override
    public void configure(JobConf job) {
      System.setProperty("java.awt.headless", "true");
      super.configure(job);
      this.queryRange = OperationsParams.getShape(job, "rect");
      this.drawMbr = queryRange != null ? queryRange.getMBR() : ImageOutputFormat.getFileMBR(job);
      this.imageWidth = job.getInt("width", 1000);
      this.imageHeight = job.getInt("height", 1000);
      this.radius = job.getInt("radius", 5);
      frequencyMap = new FrequencyMap(imageWidth, imageHeight);
    }

    @Override
    public void map(Rectangle dummy, ArrayWritable shapesAr,
        OutputCollector<NullWritable, FrequencyMap> output, Reporter reporter)
        throws IOException {
      for (Writable w : shapesAr.get()) {
        Shape s = (Shape) w;
        Point center;
        if (s instanceof Point) {
          center = (Point) s;
        } else if (s instanceof Rectangle) {
          center = ((Rectangle) s).getCenterPoint();
        } else {
          Rectangle shapeMBR = s.getMBR();
          if (shapeMBR == null)
            continue;
          center = shapeMBR.getCenterPoint();
        }
        int centerx = (int) Math.round((center.x - drawMbr.x1) * imageWidth / drawMbr.getWidth());
        int centery = (int) Math.round((center.y - drawMbr.y1) * imageHeight / drawMbr.getHeight());
        int x1 = Math.max(0, centerx - radius);
        int y1 = Math.max(0, centery - radius);
        int x2 = Math.min(imageWidth, centerx + radius);
        int y2 = Math.min(imageHeight, centery + radius);
        for (int x = x1; x < x2; x++) {
          for (int y = y1; y < y2; y++) {
            frequencyMap.frequency[x][y]++;
          }
        }
      }
      output.collect(NullWritable.get(), frequencyMap);
    }
    
  }

  public static class PlotHeatMapReduce extends MapReduceBase
      implements Reducer<NullWritable, FrequencyMap, Rectangle, ImageWritable> {
    
    private Rectangle drawMBR;
    /**Range of values to do the gradient of the heat map*/
    private MinMax valueRange;
    private boolean skipZeros;

    @Override
    public void configure(JobConf job) {
      System.setProperty("java.awt.headless", "true");
      super.configure(job);
      Shape queryRange = OperationsParams.getShape(job, "rect");
      this.drawMBR = queryRange != null ? queryRange.getMBR() : ImageOutputFormat.getFileMBR(job);
      NASAPoint.setColor1(OperationsParams.getColor(job, "color1", Color.BLUE));
      NASAPoint.setColor2(OperationsParams.getColor(job, "color2", Color.RED));
      NASAPoint.gradientType = OperationsParams.getGradientType(job, "gradient", NASAPoint.GradientType.GT_HUE);
      this.skipZeros = job.getBoolean("skipzeros", false);
      String valueRangeStr = job.get("valuerange");
      if (valueRangeStr != null) {
        String[] parts = valueRangeStr.contains("..") ? valueRangeStr.split("\\.\\.", 2) : valueRangeStr.split(",", 2);
        this.valueRange = new MinMax(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
      }
    }

    @Override
    public void reduce(NullWritable dummy, Iterator<FrequencyMap> frequencies,
        OutputCollector<Rectangle, ImageWritable> output, Reporter reporter)
        throws IOException {
      if (!frequencies.hasNext())
        return;
      FrequencyMap combined = frequencies.next().clone();
      while (frequencies.hasNext())
        combined.combine(frequencies.next());

      BufferedImage image = combined.toImage(valueRange, skipZeros);
      output.collect(drawMBR, new ImageWritable(image));
    }
  }

  /**Last submitted Plot job*/
  public static RunningJob lastSubmittedJob;
  
  private static RunningJob plotHeatMapMapReduce(Path inFile, Path outFile,
      OperationsParams params) throws IOException {
    boolean background = params.is("background");

    int width = params.getInt("width", 1000);
    int height = params.getInt("height", 1000);

    Shape plotRange = params.getShape("rect", null);

    boolean keepAspectRatio = params.is("keep-ratio", true);

    JobConf job = new JobConf(params, PlotHeatMap.class);
    job.setJobName("Plot HeatMap");

    job.setMapperClass(PlotHeatMapMap.class);
    ClusterStatus clusterStatus = new JobClient(job).getClusterStatus();
    job.setNumMapTasks(clusterStatus.getMaxMapTasks() * 5);
    job.setReducerClass(PlotHeatMapReduce.class);
    job.setNumReduceTasks(Math.max(1, clusterStatus.getMaxReduceTasks()));
    job.setMapOutputKeyClass(NullWritable.class);
    job.setMapOutputValueClass(FrequencyMap.class);

    Rectangle fileMBR;
    // Run MBR operation in synchronous mode
    OperationsParams mbrArgs = new OperationsParams(params);
    mbrArgs.setBoolean("background", false);
    fileMBR = plotRange != null ? plotRange.getMBR() :
      FileMBR.fileMBR(inFile, mbrArgs);
    LOG.info("File MBR: "+fileMBR);

    if (keepAspectRatio) {
      // Adjust width and height to maintain aspect ratio
      if (fileMBR.getWidth() / fileMBR.getHeight() > (double) width / height) {
        // Fix width and change height
        height = (int) (fileMBR.getHeight() * width / fileMBR.getWidth());
        // Make divisible by two for compatibility with ffmpeg
        height &= 0xfffffffe;
        job.setInt("height", height);
      } else {
        width = (int) (fileMBR.getWidth() * height / fileMBR.getHeight());
        job.setInt("width", width);
      }
    }

    LOG.info("Creating an image of size "+width+"x"+height);
    ImageOutputFormat.setFileMBR(job, fileMBR);
    if (plotRange != null) {
      job.setClass(SpatialSite.FilterClass, RangeFilter.class, BlockFilter.class);
    }

    job.setInputFormat(ShapeArrayInputFormat.class);
    ShapeInputFormat.addInputPath(job, inFile);

    job.setOutputFormat(ImageOutputFormat.class);
    TextOutputFormat.setOutputPath(job, outFile);

    if (background) {
      JobClient jc = new JobClient(job);
      return lastSubmittedJob = jc.submitJob(job);
    } else {
      return lastSubmittedJob = JobClient.runJob(job);
    }
  }

  private static <S extends Shape> void plotHeatMapLocal(Path inFile, Path outFile,
      OperationsParams params) throws IOException {
    int imageWidth = params.getInt("width", 1000);
    int imageHeight = params.getInt("height", 1000);
    
    Shape shape = params.getShape("shape", new Point());
    Shape plotRange = params.getShape("rect", null);

    boolean keepAspectRatio = params.is("keep-ratio", true);
    
    InputSplit[] splits;
    FileSystem inFs = inFile.getFileSystem(params);
    FileStatus inFStatus = inFs.getFileStatus(inFile);
    if (inFStatus != null && !inFStatus.isDir()) {
      // One file, retrieve it immediately.
      // This is useful if the input is a hidden file which is automatically
      // skipped by FileInputFormat. We need to plot a hidden file for the case
      // of plotting partition boundaries of a spatial index
      splits = new InputSplit[] {new FileSplit(inFile, 0, inFStatus.getLen(), new String[0])};
    } else {
      JobConf job = new JobConf(params);
      ShapeInputFormat<Shape> inputFormat = new ShapeInputFormat<Shape>();
      ShapeInputFormat.addInputPath(job, inFile);
      splits = inputFormat.getSplits(job, 1);
    }

    boolean vflip = params.is("vflip");

    Rectangle fileMBR;
    if (plotRange != null) {
      fileMBR = plotRange.getMBR();
    } else {
      fileMBR = FileMBR.fileMBR(inFile, params);
    }

    if (keepAspectRatio) {
      // Adjust width and height to maintain aspect ratio
      if (fileMBR.getWidth() / fileMBR.getHeight() > (double) imageWidth / imageHeight) {
        // Fix width and change height
        imageHeight = (int) (fileMBR.getHeight() * imageWidth / fileMBR.getWidth());
      } else {
        imageWidth = (int) (fileMBR.getWidth() * imageHeight / fileMBR.getHeight());
      }
    }
    
    // Create the frequency map
    int radius = params.getInt("radius", 5);
    FrequencyMap frequencyMap = new FrequencyMap(imageWidth, imageHeight);

    for (InputSplit split : splits) {
      ShapeRecordReader<Shape> reader = new ShapeRecordReader<Shape>(params,
          (FileSplit)split);
      Rectangle cell = reader.createKey();
      while (reader.next(cell, shape)) {
        Rectangle shapeBuffer = shape.getMBR();
        if (shapeBuffer == null)
          continue;
        shapeBuffer = shapeBuffer.buffer(radius, radius);
        if (plotRange == null || shapeBuffer.isIntersected(plotRange)) {
          Point centerPoint = shapeBuffer.getCenterPoint();
          int cx = (int) Math.round((centerPoint.x - fileMBR.x1) * imageWidth / fileMBR.getWidth());
          int cy = (int) Math.round((centerPoint.y - fileMBR.y1) * imageHeight / fileMBR.getHeight());
          frequencyMap.addPoint(cx, cy, radius);
        }
      }
      reader.close();
    }
    
    // Convert frequency map to an image with colors
    NASAPoint.setColor1(params.getColor("color1", Color.BLUE));
    NASAPoint.setColor2(params.getColor("color2", Color.RED));
    NASAPoint.gradientType = params.getGradientType("gradient", NASAPoint.GradientType.GT_HUE);
    String valueRangeStr = params.get("valuerange");
    MinMax valueRange = null;
    if (valueRangeStr != null) {
      String[] parts = valueRangeStr.contains("..") ? valueRangeStr.split("\\.\\.", 2) : valueRangeStr.split(",", 2);
      valueRange = new MinMax(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    boolean skipZeros = params.getBoolean("skipzeros", false);
    BufferedImage image = frequencyMap.toImage(valueRange, skipZeros);
    
    if (vflip) {
      AffineTransform tx = AffineTransform.getScaleInstance(1, -1);
      tx.translate(0, -image.getHeight());
      AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
      image = op.filter(image, null);
    }
    FileSystem outFs = outFile.getFileSystem(params);
    OutputStream out = outFs.create(outFile, true);
    ImageIO.write(image, "png", out);
    out.close();

  }

  public static RunningJob plotHeatMap(Path inFile, Path outFile, OperationsParams params) throws IOException {
    // Determine the size of input which needs to be processed in order to determine
    // whether to plot the file locally or using MapReduce
    boolean isLocal;
    if (params.get("local") == null) {
      JobConf job = new JobConf(params);
      ShapeInputFormat<Shape> inputFormat = new ShapeInputFormat<Shape>();
      ShapeInputFormat.addInputPath(job, inFile);
      Shape plotRange = params.getShape("rect");
      if (plotRange != null) {
        job.setClass(SpatialSite.FilterClass, RangeFilter.class, BlockFilter.class);
      }
      InputSplit[] splits = inputFormat.getSplits(job, 1);
      boolean autoLocal = splits.length <= 3;
      
      isLocal = params.is("local", autoLocal);
    } else {
      isLocal = params.is("local");
    }
    
    if (isLocal) {
      plotHeatMapLocal(inFile, outFile, params);
      return null;
    } else {
      return plotHeatMapMapReduce(inFile, outFile, params);
    }
  }

  private static void printUsage() {
    System.out.println("Plots all shapes to an image");
    System.out.println("Parameters: (* marks required parameters)");
    System.out.println("<input file> - (*) Path to input file");
    System.out.println("<output file> - (*) Path to output file");
    System.out.println("shape:<point|rectangle|polygon|ogc> - (*) Type of shapes stored in input file");
    System.out.println("width:<w> - Maximum width of the image (1000)");
    System.out.println("height:<h> - Maximum height of the image (1000)");
    System.out.println("radius:<r> - Radius used when smoothing the heat map");
    System.out.println("valuerange:<min,max> - Range of values for plotting the heat map");
    System.out.println("color1:<c> - Color to use for minimum values");
    System.out.println("color2:<c> - Color to use for maximum values");
    System.out.println("gradient:<hue|color> - Method to change gradient from color1 to color2");
    System.out.println("-overwrite: Override output file without notice");
    System.out.println("-vflip: Vertically flip generated image to correct +ve Y-axis direction");
    
    GenericOptionsParser.printGenericCommandUsage(System.out);
  }
  
  /**
   * @param args
   * @throws IOException 
   */
  public static void main(String[] args) throws IOException {
    System.setProperty("java.awt.headless", "true");
    OperationsParams params = new OperationsParams(new GenericOptionsParser(args));
    if (!params.checkInputOutput()) {
      printUsage();
      System.exit(1);
    }
    
    Path inFile = params.getInputPath();
    Path outFile = params.getOutputPath();

    long t1 = System.currentTimeMillis();
    plotHeatMap(inFile, outFile, params);
    long t2 = System.currentTimeMillis();
    System.out.println("Plot heat map finished in "+(t2-t1)+" millis");
  }

}