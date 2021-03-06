package org.boofcv.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ToggleButton;
import boofcv.abst.feature.detect.interest.ConfigGeneralDetector;
import boofcv.abst.feature.tracker.PointTracker;
import boofcv.abst.sfm.AccessPointTracks;
import boofcv.abst.sfm.d2.ImageMotion2D;
import boofcv.alg.sfm.d2.StitchingFromMotion2D;
import boofcv.android.ConvertBitmap;
import boofcv.android.gui.VideoRenderProcessing;
import boofcv.factory.feature.tracker.FactoryPointTracker;
import boofcv.factory.sfm.FactoryMotion2D;
import boofcv.misc.BoofMiscOps;
import boofcv.struct.image.ImageSInt16;
import boofcv.struct.image.ImageType;
import boofcv.struct.image.ImageUInt8;
import georegression.struct.affine.Affine2D_F64;
import georegression.struct.homography.Homography2D_F64;
import georegression.struct.point.Point2D_F64;
import georegression.transform.homography.HomographyPointOps_F64;
import org.ddogleg.struct.FastQueue;

import java.util.List;

/**
 * Displays an image mosaic created from the video stream.
 *
 * @author Peter Abeles
 */
public class MosaicDisplayActivity extends DemoVideoDisplayActivity
implements CompoundButton.OnCheckedChangeListener
{

	Paint paintInlier;
	Paint paintOutlier;

	boolean showFeatures;
	boolean resetRequested;
	boolean paused = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		paintInlier = new Paint();
		paintInlier.setColor(Color.RED);
		paintInlier.setStyle(Paint.Style.FILL);
		paintOutlier = new Paint();
		paintOutlier.setColor(Color.BLUE);
		paintOutlier.setStyle(Paint.Style.FILL);

		resetRequested = false;

		LayoutInflater inflater = getLayoutInflater();
		LinearLayout controls = (LinearLayout)inflater.inflate(R.layout.mosaic_controls,null);

		LinearLayout parent = getViewContent();
		parent.addView(controls);

		CheckBox seek = (CheckBox)controls.findViewById(R.id.check_features);
		seek.setOnCheckedChangeListener(this);
	}

	@Override
	protected void onResume() {
		super.onResume();
		StitchingFromMotion2D<ImageUInt8,Affine2D_F64> distortAlg = createStabilization();
		setProcessing(new PointProcessing(distortAlg));
	}

	public void resetPressed( View view ) {
		resetRequested = true;
	}

	public void pausedPressed( View view ) {
		paused = !((ToggleButton)view).isChecked();
	}

	private StitchingFromMotion2D<ImageUInt8,Affine2D_F64> createStabilization() {

		ConfigGeneralDetector config = new ConfigGeneralDetector();
		config.maxFeatures = 150;
		config.threshold = 40;
		config.radius = 3;

		PointTracker<ImageUInt8> tracker = FactoryPointTracker.
				klt(new int[]{1, 2,4}, config, 3, ImageUInt8.class, ImageSInt16.class);

		ImageMotion2D<ImageUInt8,Affine2D_F64> motion = FactoryMotion2D.createMotion2D(100, 1.5, 2, 40,
				0.5, 0.6, false,tracker, new Affine2D_F64());

		return FactoryMotion2D.createVideoStitch(0.2,motion,ImageUInt8.class);
	}

	@Override
	public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
		showFeatures = b;
	}

	protected class PointProcessing extends VideoRenderProcessing<ImageUInt8> {
		StitchingFromMotion2D<ImageUInt8,Affine2D_F64> alg;
		Homography2D_F64 imageToDistorted = new Homography2D_F64();
		Homography2D_F64 distortedToImage = new Homography2D_F64();

		Bitmap bitmap;
		byte[] storage;

		StitchingFromMotion2D.Corners corners = new StitchingFromMotion2D.Corners();
		Point2D_F64 distPt = new Point2D_F64();

		FastQueue<Point2D_F64> inliersGui = new FastQueue<Point2D_F64>(Point2D_F64.class,true);
		FastQueue<Point2D_F64> outliersGui = new FastQueue<Point2D_F64>(Point2D_F64.class,true);

		public PointProcessing( StitchingFromMotion2D<ImageUInt8,Affine2D_F64> alg  ) {
			super(ImageType.single(ImageUInt8.class));
			this.alg = alg;
		}

		@Override
		protected void declareImages(int width, int height) {
			super.declareImages(width, height);

			outputWidth = width*2;
			outputHeight = height;

			int tx = outputWidth/2 - width/4;
			int ty = outputHeight/2 - height/4;

			Affine2D_F64 init = new Affine2D_F64(0.5,0,0,0.5,tx,ty);
			init = init.invert(null);

			alg.configure(outputWidth,outputHeight,init);
			bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
			storage = ConvertBitmap.declareStorage(bitmap, storage);
		}

		@Override
		protected void process(ImageUInt8 gray) {
			if(paused)
				return;

			if( !resetRequested && alg.process(gray) ) {
				ImageUInt8 stitched = alg.getStitchedImage();

				synchronized ( lockGui ) {
					ConvertBitmap.grayToBitmap(stitched,bitmap,storage);

					ImageMotion2D<?,?> motion = alg.getMotion();
					if( showFeatures && (motion instanceof AccessPointTracks) ) {
						AccessPointTracks access = (AccessPointTracks)motion;

						alg.getWorldToCurr(imageToDistorted);
						imageToDistorted.invert(distortedToImage);
						inliersGui.reset();outliersGui.reset();
						List<Point2D_F64> points = access.getAllTracks();
						for( int i = 0; i < points.size(); i++ ) {
							HomographyPointOps_F64.transform(distortedToImage,points.get(i),distPt);

							if( access.isInlier(i) ) {
								inliersGui.grow().set(distPt.x,distPt.y);
							} else {
								outliersGui.grow().set(distPt.x,distPt.y);
							}
						}
					}

					alg.getImageCorners(gray.width,gray.height,corners);
				}

				boolean inside = true;
				inside &= BoofMiscOps.checkInside(stitched,corners.p0.x,corners.p0.y,5);
				inside &= BoofMiscOps.checkInside(stitched,corners.p1.x,corners.p1.y,5);
				inside &= BoofMiscOps.checkInside(stitched,corners.p2.x,corners.p2.y,5);
				inside &= BoofMiscOps.checkInside(stitched,corners.p3.x,corners.p3.y,5);
				if( !inside ) {
					alg.setOriginToCurrent();
				}



			} else {
				resetRequested = false;
				alg.reset();
			}
		}

		@Override
		protected void render(Canvas canvas, double imageToOutput) {
			canvas.drawBitmap(bitmap,0,0,null);

			Point2D_F64 p0 = corners.p0;
			Point2D_F64 p1 = corners.p1;
			Point2D_F64 p2 = corners.p2;
			Point2D_F64 p3 = corners.p3;

			canvas.drawLine((int)p0.x,(int)p0.y,(int)p1.x,(int)p1.y, paintInlier);
			canvas.drawLine((int)p1.x,(int)p1.y,(int)p2.x,(int)p2.y, paintInlier);
			canvas.drawLine((int)p2.x,(int)p2.y,(int)p3.x,(int)p3.y, paintInlier);
			canvas.drawLine((int)p3.x,(int)p3.y,(int)p0.x,(int)p0.y, paintInlier);

			for( int i = 0; i < inliersGui.size; i++ ) {
				Point2D_F64 p = inliersGui.get(i);
				canvas.drawCircle((float)p.x,(float)p.y,3,paintInlier);
			}
			for( int i = 0; i < outliersGui.size; i++ ) {
				Point2D_F64 p = outliersGui.get(i);
				canvas.drawCircle((float)p.x,(float)p.y,3,paintOutlier);
			}
		}
	}
}