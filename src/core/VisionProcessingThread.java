package core;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import boofcv.alg.color.ColorHsv;
import boofcv.alg.feature.detect.edge.CannyEdge;
import boofcv.alg.filter.binary.BinaryImageOps;
import boofcv.alg.filter.binary.Contour;
import boofcv.alg.filter.binary.ThresholdImageOps;
import boofcv.alg.misc.PixelMath;
import boofcv.alg.shapes.ShapeFittingOps;
import boofcv.factory.feature.detect.edge.FactoryEdgeDetectors;
import boofcv.gui.feature.VisualizeShapes;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.ConnectRule;
import boofcv.struct.PointIndex_I32;
import boofcv.struct.image.ImageFloat32;
import boofcv.struct.image.ImageSInt16;
import boofcv.struct.image.ImageUInt8;
import boofcv.struct.image.MultiSpectral;
import server.NetUtils;
import targeting.ImageConversion;
import targeting.TargetingUtils;
import targeting.ValueHistory;
import targeting.ball.BallTarget;
import targeting.tower.TowerTarget;;

public class VisionProcessingThread extends Thread{

   //camera resolution
   private Dimension m_camRes; 
   //core interface and webcam variables
   private CameraInterface m_webcam;
   private MultiSpectral<ImageUInt8> m_image;

   //loop as long as this is true
   private boolean m_running = true;

   //do different things depending on the target
   Constants.TargetType m_target;

   //debug display object
   private DebugDisplay m_display;
   public static boolean m_showDisplay = false;

   //history of values found
   ValueHistory m_targetHistory;

   public VisionProcessingThread(int camIndex, Constants.TargetType target){
      this("VisionProcessingThread", camIndex, target);
   }
   public VisionProcessingThread(String name, int camIndex, Constants.TargetType target){
      super(name);
      //set target
      m_target = target;
      //init history for data
      m_targetHistory = new ValueHistory(m_target);
      //init webcam
      if(target == Constants.TargetType.ball){
         m_camRes = Constants.MED_RES;
      }
      else {
         m_camRes = Constants.MAX_RES;
      }
      m_webcam = new CameraInterface(camIndex, m_camRes);
      //init display
      if(m_showDisplay) {
         System.out.println("[INFO] Initializing display");

         //error catching if for example the system is headless
         try {
            String title = "Targeting: ";
            if(target == Constants.TargetType.ball){
               title += "Ball";
            }
            else {
               title += "Tower";
            }
            m_display = new DebugDisplay(m_camRes, title);
            System.out.println("[INFO] Display initializion succeeded");
         } catch (Exception e) {
            System.out.println("[ERROR] Cannot initialize display, continuing without one...");
         }
      }
   }

   public void run() {
      while(m_running) {
         
         if (VisionServerThread.sendCount > 0) {
          
            //encoder data to append as a timestamp
            byte[] encoderData = VisionServerThread.receivedData;
            
            //m_image is the current webcam image
            m_image = m_webcam.getImage();
            
            //the array to send to the rio
            byte[] values = findTower(encoderData);
            
            //Send data to RIO
            NetUtils.SendValues(values);

            if (m_showDisplay) {
               //update the image on the display
               m_display.setImageRGB(m_image);
            }
         }
         
      }
   }


   
   
   //code to find the tower
   private byte[] findTower(byte[] encoderValues) {
      int[] towerData = new int[3];
      
      //image to store thresholded image
      ImageUInt8 filtered = new ImageUInt8(m_image.width, m_image.height);

      //value to threshold by
      int thresholdVal = 230;
      
      //thresholds all color channels by thresholdVal
      for(int x = 0; x < m_image.width; x++){
         for(int y = 0; y < m_image.height; y++){
            int redPixelVal = m_image.getBand(0).get(x, y);
            int greenPixelVal = m_image.getBand(1).get(x, y);
            int bluePixelVal = m_image.getBand(2).get(x, y);
            
            if (redPixelVal > thresholdVal && greenPixelVal > thresholdVal && bluePixelVal > thresholdVal) {
                filtered.set(x, y, 1);
            }
            else {
                filtered.set(x, y, 0);
            }
         }
      }
      
      //edge detects the thresholded image
      CannyEdge<ImageUInt8, ImageSInt16> canny = FactoryEdgeDetectors.canny(2, true, true, ImageUInt8.class, ImageSInt16.class);
      canny.process(filtered, 0.1f, 0.3f, filtered);
      
      //make a copy of the image here for display purposes
      ImageUInt8 displayer = filtered.clone();
      PixelMath.multiply(displayer, 255, displayer);
      //create some objects for drawing the image to the screen
      BufferedImage gImage = new BufferedImage(filtered.width, filtered.height, 4);
      Graphics2D g = gImage.createGraphics();
      g.setStroke(new BasicStroke(2));
      ConvertBufferedImage.convertTo(displayer, gImage, true);
      
      //get a list of contours from the thresholded image
      List<Contour> contours = BinaryImageOps.contour(filtered, ConnectRule.EIGHT, null);
      //list of potential towers
      List<TowerTarget> targets = new ArrayList<TowerTarget>();
      
      for(Contour c : contours){
         List<PointIndex_I32> vertexes = ShapeFittingOps.fitPolygon(c.external,true,0.05,0,100);
         
         TargetingUtils.smoothContour(vertexes, 10);
         
         TowerTarget possibleTarget = new TowerTarget(vertexes, m_camRes);
         
         if(vertexes.size() > 5 && vertexes.size() < 9
                 /*&& largeAngle < 2.4*/){
            targets.add(possibleTarget);
         }
      }

      //we don't want to look for the central target. We should assume the target can be anywhere. tsk tsk
      TowerTarget squareTarget = null;

      for(TowerTarget t : targets){
         if(squareTarget == null){
            squareTarget = t;
         }
         else {
            double oldsquare = squareTarget.getSquareness();
            double newsquare = t.getSquareness();

            if(newsquare > oldsquare){
               squareTarget = t;
            }
         }
      }

      if(squareTarget != null && m_showDisplay){
         g.setColor(Color.GREEN);
         VisualizeShapes.drawPolygon(squareTarget.m_bounds, true, g);
         g.drawOval((int)(squareTarget.getCenter().x - 2.5), (int)(squareTarget.getCenter().y - 2.5), 5, 5);
      }

      m_image = ImageConversion.toMultiSpectral(gImage);

      /*
       * Format for data:
       * [0] - tower flag (1)
       * [1] - x center
       * [2] y center
       */
      towerData[0] = Constants.TOWER_FLAG;
      if(squareTarget != null) {
         towerData[1] = squareTarget.getCenter().x;
         towerData[2] = squareTarget.getCenter().y;
      }
      else {
         for(int i = 1; i < towerData.length; i++){
            towerData[i] = 0;
         }
      }
      
      //m_targetHistory.updateHistory(towerData);
      //towerData = m_targetHistory.m_currData;
      
      System.out.println(Arrays.toString(towerData));
      
      byte[] encodedDataFull = new byte[20];
      byte[] encodedTowerData = NetUtils.intsToBytes(towerData);
      for (int i = 0; i < encodedTowerData.length; i++) {
         encodedDataFull[i] = encodedTowerData[i];
      }
      for (int i = 0; i < encoderValues.length; i++) {
         encodedDataFull[i + 12] = encoderValues[i];
      }
      
      return encodedDataFull;
   }

   //code to find the ball
   private int[] findBall() {
      int[] ballData = new int[Constants.BALL_SIZE];
      Dimension cropPos = new Dimension(50, 70);
      Dimension cropSize = new Dimension(220, 130);

      MultiSpectral<ImageFloat32> hsvImage = new MultiSpectral<ImageFloat32>(ImageFloat32.class, m_camRes.width, m_camRes.height, 3);
      ImageUInt8 valueBand = new ImageUInt8(m_camRes.width, m_camRes.height);
      
      //sets m_hsvImage to the hsv version of the source image
      ColorHsv.rgbToHsv_F32(
            ImageConversion.MultiSpectralUInt8ToFloat32(m_image),
            hsvImage);

      //extracts just the value band from the hsv image
      //ConvertImage.convert(hsvImage.getBand(2), valueBand);
      valueBand = m_image.getBand(2).clone();
      //valueBand = m_image.getBand(0).clone();
      
      for(int x=0;x<valueBand.width;x++){
          for(int y=0;y<valueBand.height;y++){
              int minX = cropPos.width;
              int minY = cropPos.height;
              int maxX = cropPos.width + cropSize.width;
              int maxY = cropPos.height + cropSize.height;
              if(x < minX || x > maxX || y < minY || y > maxY){
                  valueBand.set(x, y, 0);
              }
              else {
                  int pixel = valueBand.get(x, y);
                  if(pixel < 110){
                      pixel = 0;
                  }
                  valueBand.set(x, y, pixel);
              }
          }
      }
      
      ImageUInt8 displayer = valueBand.clone();
      //PixelMath.multiply(displayer, 255, displayer);
      
      //threshold the image to make the ball clear
      valueBand = ThresholdImageOps.localSquare(valueBand, null, 20, 0.98f, true, null, null);
      
      //edge detect to locate the ball
      CannyEdge<ImageUInt8, ImageSInt16> canny = FactoryEdgeDetectors.canny(2,true, true, ImageUInt8.class, ImageSInt16.class);
      canny.process(valueBand, 0.26f, 1f, valueBand);
      
      //objects and settings to display the found ball on the display window
      BufferedImage gImage = null;
      Graphics2D g = null;
      if(m_showDisplay){
         gImage = new BufferedImage(valueBand.width, valueBand.height, 4);
         g = gImage.createGraphics();
         g.setStroke(new BasicStroke(2));

         ConvertBufferedImage.convertTo(displayer, gImage, true);
      }

      //creates contours from the edge detection done earlier
      List<Contour> contours = BinaryImageOps.contour(valueBand, ConnectRule.EIGHT, null);

      //list to store all valid ellipses
      List<BallTarget> validEllipses = new ArrayList<BallTarget>();

      for(Contour c : contours){
         List<PointIndex_I32> vertexes = ShapeFittingOps.fitPolygon(c.external, false, 0.05, 0, 180);

         BallTarget circle = new BallTarget(vertexes, m_camRes);

         double ratio = circle.m_shape.a / circle.m_shape.b;
         if(ratio < 1){
            ratio = 1 / ratio;
         }

         double averageRadius = (circle.m_shape.a + circle.m_shape.b) / 2;
         boolean exceedsFrame = false;
         if(averageRadius > cropSize.height || averageRadius > cropSize.width){
             exceedsFrame = true;
         }

         if(ratio < 1.2 && averageRadius > 30
                 && !exceedsFrame
               /*&& verticalDeviation < 20*/){
            validEllipses.add(circle);
         }
      }

      BallTarget ball = TargetingUtils.largestArea(validEllipses);

      if(m_showDisplay){
         m_display.clearBuffer();

         if(ball != null){
            m_display.setColor(Color.CYAN);
            m_display.setEllipse(ball.m_shape);
         }

         m_image = ImageConversion.toMultiSpectral(gImage);
      }

      ballData[0] = Constants.BALL_FLAG;
      if(ball != null){
         ballData[1] = (int)Math.round(ball.getAverageRadius());
         ballData[2] = ball.getCenter().x;
         ballData[3] = ball.getCenter().y;
      }
      else {
         for(int i = 1; i < ballData.length; i++){
            ballData[i] = 0;
         }
      }

      m_targetHistory.updateHistory(ballData);
      System.out.println(Arrays.toString(m_targetHistory.m_currData));

      return m_targetHistory.m_currData;
   }

}