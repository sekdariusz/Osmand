package net.osmand.router;

import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import net.osmand.LogUtil;
import net.osmand.NativeLibrary;
import net.osmand.NativeLibrary.NativeRouteSearchResult;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapIndexReader.SearchRequest;
import net.osmand.binary.BinaryMapRouteReaderAdapter;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteSubregion;
import net.osmand.binary.RouteDataObject;
import net.osmand.router.BinaryRoutePlanner.RouteSegment;
import net.osmand.router.BinaryRoutePlanner.RouteSegmentVisitor;

import org.apache.commons.logging.Log;



public class RoutingContext {
	
	private final static Log log = LogUtil.getLog(RoutingContext.class);
	// Final context variables
	public final RoutingConfiguration config;
	public final NativeLibrary nativeLib;
	public final Map<BinaryMapIndexReader, List<RouteSubregion>> map = new LinkedHashMap<BinaryMapIndexReader, List<RouteSubregion>>();
	public final Map<RouteRegion, BinaryMapIndexReader> reverseMap = new LinkedHashMap<RouteRegion, BinaryMapIndexReader>();
	
	// 1. Initial variables
	private int relaxingIteration = 0;
	public long firstRoadId = 0;
	public int firstRoadDirection = 0;
	
	public Interruptable interruptable;
	public List<RouteSegmentResult> previouslyCalculatedRoute;

	// 2. Routing memory cache (big objects)
	TIntObjectHashMap<RoutingTile> tiles = new TIntObjectHashMap<RoutingContext.RoutingTile>();
	// need to be array list
	List<RoutingSubregionTile> subregionTiles = new ArrayList<RoutingSubregionTile>();
	
	// 3. Warm object caches
	TLongSet nonRestrictedIds = new TLongHashSet();
	ArrayList<RouteSegment> segmentsToVisitPrescripted = new ArrayList<BinaryRoutePlanner.RouteSegment>(5);
	ArrayList<RouteSegment> segmentsToVisitNotForbidden = new ArrayList<BinaryRoutePlanner.RouteSegment>(5);
	TLongObjectHashMap<RouteDataObject> excludeDuplications = new TLongObjectHashMap<RouteDataObject>();

	
	// 4. Final results
	RouteSegment finalDirectRoute = null;
	int finalDirectEndSegment = 0;
	RouteSegment finalReverseRoute = null;
	int finalReverseEndSegment = 0;


	// 5. debug information (package accessor)
	long timeToLoad = 0;
	long timeToLoadHeaders = 0;
	long timeToFindInitialSegments = 0;
	long timeToCalculate = 0;
	public int loadedTiles = 0;
	int distinctLoadedTiles = 0;
	int maxLoadedTiles = 0;
	int loadedPrevUnloadedTiles = 0;
	int unloadedTiles = 0;
	public int visitedSegments = 0;
	public int relaxedSegments = 0;
	// callback of processing segments
	RouteSegmentVisitor visitor = null;
	
	private TileStatistics global = new TileStatistics();
	private static final boolean SHOW_GC_SIZE = false;
	
	
	
	public RoutingContext(RoutingContext cp) {
		this.config = cp.config;
		this.map.putAll(cp.map);
		this.reverseMap.putAll(cp.reverseMap);
		this.nativeLib = cp.nativeLib;
		// copy local data and clear caches
		for(RoutingSubregionTile tl : subregionTiles) {
			if(tl.isLoaded()) {
				subregionTiles.add(tl);
				for (RouteSegment rs : tl.routes.valueCollection()) {
					RouteSegment s = rs;
					while (s != null) {
						s.parentRoute = null;
						s.parentSegmentEnd = 0;
						s.distanceFromStart = 0;
						s.distanceToEnd = 0;
						s = s.next;
					}
				}
			}
		}
	}
	
	public RoutingContext(RoutingConfiguration config, NativeLibrary nativeLibrary, BinaryMapIndexReader[] map) {
		for (BinaryMapIndexReader mr : map) {
			List<RouteRegion> rr = mr.getRoutingIndexes();
			List<RouteSubregion> subregions = new ArrayList<BinaryMapRouteReaderAdapter.RouteSubregion>();
			for (RouteRegion r : rr) {
				for (RouteSubregion rs : r.getSubregions()) {
					subregions.add(new RouteSubregion(rs));
				}
				this.reverseMap.put(r, mr);
			}
			this.map.put(mr, subregions);
		}
		this.config = config;
		// FIXME not commit
//		this.nativeLib = nativeLibrary;
		this.nativeLib = null;
	}
	
	
	public RouteSegmentVisitor getVisitor() {
		return visitor;
	}
	
	public int getCurrentlyLoadedTiles() {
		int cnt = 0;
		for(RoutingSubregionTile t : this.subregionTiles){
			if(t.isLoaded()) {
				cnt++;
			}
		}
		return cnt;
	}
	
	public int getCurrentEstimatedSize(){
		return global.size;
	}
	
	public boolean runRelaxingStrategy(){
		if(!isUseRelaxingStrategy()){
			return false;
		}
		relaxingIteration++;
		if(relaxingIteration > config.ITERATIONS_TO_RELAX_NODES){
			relaxingIteration = 0;
			return true;
		}
		return false;
	}
	
	public void setVisitor(RouteSegmentVisitor visitor) {
		this.visitor = visitor;
	}

	public boolean isUseDynamicRoadPrioritising() {
		return config.useDynamicRoadPrioritising;
	}
	
	public int getDynamicRoadPriorityDistance() {
		return config.dynamicRoadPriorityDistance;
	}

	public boolean isUseRelaxingStrategy() {
		return config.useRelaxingStrategy;
	}
	
	public void setUseRelaxingStrategy(boolean useRelaxingStrategy) {
		config.useRelaxingStrategy = useRelaxingStrategy;
	}
	
	public void setUseDynamicRoadPrioritising(boolean useDynamicRoadPrioritising) {
		config.useDynamicRoadPrioritising = useDynamicRoadPrioritising;
	}

	public void setRouter(VehicleRouter router) {
		config.router = router;
	}
	
	public void setHeuristicCoefficient(double heuristicCoefficient) {
		config.heuristicCoefficient = heuristicCoefficient;
	}

	public VehicleRouter getRouter() {
		return config.router;
	}

	public boolean planRouteIn2Directions() {
		return config.planRoadDirection == 0;
	}

	public int getPlanRoadDirection() {
		return config.planRoadDirection;
	}

	public void setPlanRoadDirection(int planRoadDirection) {
		config.planRoadDirection = planRoadDirection;
	}

	public int roadPriorityComparator(double o1DistanceFromStart, double o1DistanceToEnd, double o2DistanceFromStart, double o2DistanceToEnd) {
		return BinaryRoutePlanner.roadPriorityComparator(o1DistanceFromStart, o1DistanceToEnd, o2DistanceFromStart, o2DistanceToEnd,
				config.heuristicCoefficient);
	}
	
	public void registerRouteDataObject(int x31, int y31, RouteDataObject o ) {
		if(!getRouter().acceptLine(o)){
			return;
		}
		getRoutingTile(x31, y31, false).registerRouteDataObject(o);
	}
	
	public void unloadAllData() {
		unloadAllData(null);
	}
	
	public void unloadAllData(RoutingContext except) {
//		List<RoutingTile> toUnload = new ArrayList<RoutingContext.RoutingTile>();
//		for(RoutingTile t : local.tiles.valueCollection()){
//			if(!ctx.tiles.contains(t.getId())) {
//				toUnload.add(t);
//			}
//		}
//		for(RoutingTile tl : toUnload) {
//			local.unloadTile(tl, false);
//		}
		
		for (RoutingSubregionTile tl : subregionTiles) {
			if (tl.isLoaded()) {
				if(except == null || except.searchSubregionTile(tl.subregion) < 0){
					tl.unload();
				}
			}
		}
	}
	
	private int searchSubregionTile(RouteSubregion subregion){
		RoutingSubregionTile key = new RoutingSubregionTile(subregion);
		int ind = Collections.binarySearch(subregionTiles, key, new Comparator<RoutingSubregionTile>() {
			@Override
			public int compare(RoutingSubregionTile o1, RoutingSubregionTile o2) {
				if(o1.subregion.left == o2.subregion.left) {
					return 0;
				}
				return o1.subregion.left < o2.subregion.left ? 1 : -1;
			}
		});
		if (ind >= 0) {
			for (int i = ind; i <= subregionTiles.size(); i++) {
				if (i == subregionTiles.size() || subregionTiles.get(i).subregion.left > subregion.left) {
					ind = -i - 1;
					return ind;
				}
				if (subregionTiles.get(i).subregion == subregion) {
					return i;
				}
			}
		}
		return ind;
	}
	
	
	
	public RouteSegment loadRouteSegment(int x31, int y31) {
		final RoutingTile tile = getRoutingTile(x31, y31, true);
		return tile.getSegment(x31, y31, this);
	}
	
	private void loadSubregionTile(final RoutingSubregionTile ts) {
		boolean wasUnloaded = ts.isUnloaded();
		if (nativeLib == null) {
			long now = System.nanoTime();
			try {
				BinaryMapIndexReader reader = reverseMap.get(ts.subregion.routeReg);
				ts.setLoadedNonNative();
				List<RouteDataObject> res = reader.loadRouteIndexData(ts.subregion);
				for(RouteDataObject ro : res){
					if(ro != null && config.router.acceptLine(ro)) {
						ts.add(ro);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException("Loading data exception", e);
			}

			timeToLoad += (System.nanoTime() - now);
		} else {
			throw new UnsupportedOperationException();
			// try {
			// r.getKey().initRouteRegionsIfNeeded(request);
			// } catch (IOException e) {
			// throw new RuntimeException("Loading data exception", e);
			// }
			// for(RouteRegion reg : r.getKey().getRoutingIndexes()) {
			// NativeRouteSearchResult rs = nativeLoadRegion(request, reg, nativeLib, loadData);
			// if(rs != null) {
			// if(!loadData){
			// if (rs.nativeHandler != 0) {
			// nativeRouteSearchResults.add(rs);
			// }
			// } else {
			// if(rs.objects != null){
			// for(RouteDataObject ro : rs.objects) {
			// if(ro != null) {
			// request.publish(ro);
			// }
			// }
			// }
			// }
			// }
			// }
		}
		loadedTiles++;
		if (wasUnloaded) {
			loadedPrevUnloadedTiles++;
		} else {
			if(global != null) {
				global.allRoutes += ts.tileStatistics.allRoutes;
				global.coordinates += ts.tileStatistics.coordinates;
			}
			distinctLoadedTiles++;
		}
		global.size += ts.tileStatistics.size;
	}

	private void loadTileHeaders(RoutingTile tile) {
		final int zoomToLoad = 31 - tile.getZoom();
		final int tileX = tile.getTileX();
		final int tileY = tile.getTileY();
		
		SearchRequest<RouteDataObject> request = BinaryMapIndexReader.buildSearchRouteRequest(tileX << zoomToLoad,
				(tileX + 1) << zoomToLoad, tileY << zoomToLoad, (tileY + 1) << zoomToLoad, null);
		for (Entry<BinaryMapIndexReader, List<RouteSubregion>> r : map.entrySet()) {
			if(nativeLib == null) {
				try {
					if (r.getValue().size() > 0) {
						long now = System.nanoTime();
//						int rg = r.getValue().get(0).routeReg.regionsRead;
						List<RouteSubregion> subregs = r.getKey().searchRouteIndexTree(request, r.getValue());
						for(RouteSubregion sr : subregs) {
							List<RoutingTile> tiles = getRoutingTiles(sr.left, sr.top, sr.right, sr.bottom);
							int ind = searchSubregionTile(sr);
							RoutingSubregionTile found;
							if(ind < 0){
								found = new RoutingSubregionTile(sr);
								subregionTiles.add(-(ind+1), found);
							} else {
								found = subregionTiles.get(ind);
							}
							for(RoutingTile t : tiles) {
								t.searchSubregionAndAdd(sr, found);
							}
						}
						timeToLoadHeaders += (System.nanoTime() - now);
					}
				} catch (IOException e) {
					throw new RuntimeException("Loading data exception", e);
				}
				
			} else {
				throw new UnsupportedOperationException();
			}
		}
		tile.setHeadersLoaded();
		
	}

	public void loadTileData(int x31, int y31, int zoomAround, final List<RouteDataObject> toFillIn) {
		int coordinatesShift = (1 << (31 - zoomAround));
		// put in map to avoid duplicate map loading
		TIntObjectHashMap<RoutingTile> ts = new TIntObjectHashMap<RoutingContext.RoutingTile>();
		long now = System.nanoTime();
		RoutingTile rt = getRoutingTile(x31 - coordinatesShift, y31 - coordinatesShift, true);
		ts.put(rt.getId(), rt);
		rt = getRoutingTile(x31 + coordinatesShift, y31 - coordinatesShift, true);
		ts.put(rt.getId(), rt);
		rt = getRoutingTile(x31 - coordinatesShift, y31 + coordinatesShift, true);
		ts.put(rt.getId(), rt);
		rt = getRoutingTile(x31 + coordinatesShift, y31 + coordinatesShift, true);
		ts.put(rt.getId(), rt);

		Iterator<RoutingTile> it = ts.valueCollection().iterator();
		
		while (it.hasNext()) {
			RoutingTile tl = it.next();
			tl.getAllObjects(toFillIn, this);
		}
		timeToLoadHeaders += (System.nanoTime() - now);
	}
	
	private RoutingTile getRoutingTile(int x31, int y31, boolean load){
		int xloc = x31 >> (31 - config.ZOOM_TO_LOAD_TILES);
		int yloc = y31 >> (31 - config.ZOOM_TO_LOAD_TILES);
		int l = (xloc << config.ZOOM_TO_LOAD_TILES) + yloc;
		RoutingTile tile = tiles.get(l);
		if(tile == null) {
			tile = new RoutingTile(xloc, yloc, config.ZOOM_TO_LOAD_TILES);
			tiles.put(l, tile);
		}
		if (load) {
			if (getCurrentEstimatedSize() > 0.95 * config.memoryLimitation) {
				int sz1 = getCurrentEstimatedSize();
				long h1 = 0;
				if (SHOW_GC_SIZE && sz1 > 0.7 * config.memoryLimitation) {
					runGCUsedMemory();
					h1 = runGCUsedMemory();
				}
				int clt = getCurrentlyLoadedTiles();
				unloadUnusedTiles();
				if (h1 != 0 && getCurrentlyLoadedTiles() != clt) {
					int sz2 = getCurrentEstimatedSize();
					runGCUsedMemory();
					long h2 = runGCUsedMemory();
					float mb = (1 << 20);
					log.warn("Unload tiles :  estimated " + (sz1 - sz2) / mb + " ?= " + (h1 - h2) / mb + " actual");
					log.warn("Used after " + h2 / mb + " of " + Runtime.getRuntime().totalMemory() / mb + " max "
							+ Runtime.getRuntime().maxMemory() / mb);
				} else {
//					float mb = (1 << 20);
//					int sz2 = getCurrentEstimatedSize();
//					log.warn("Unload tiles :  occupied before " + sz1 / mb + " Mb - now  " + sz2 / mb + "MB ");
//					log.warn("Memory free " + Runtime.getRuntime().freeMemory() / mb + " of " + Runtime.getRuntime().totalMemory() / mb
//							+ " max " + Runtime.getRuntime().maxMemory() / mb);
				}
			}
			if (!tile.isHeadersLoaded()) {
				loadTileHeaders(tile);
			}
			for (RoutingSubregionTile ts : tile.subregions) {
				if (!ts.isLoaded()) {
					loadSubregionTile(ts);
				}
			}
		}
		return tile;
	}
	
	private List<RoutingTile> getRoutingTiles(int leftX, int topY, int rightX, int bottomY) {
		int xl = leftX >> (31 - config.ZOOM_TO_LOAD_TILES);
		int yt = topY >> (31 - config.ZOOM_TO_LOAD_TILES);
		int xr = rightX >> (31 - config.ZOOM_TO_LOAD_TILES);
		int yb = bottomY >> (31 - config.ZOOM_TO_LOAD_TILES);
		List<RoutingTile> res = new ArrayList<RoutingContext.RoutingTile>();
		for (int xloc = xl; xloc <= xr; xloc++) {
			for (int yloc = yt; yloc <= yb; yloc++) {
				int l = (xloc << config.ZOOM_TO_LOAD_TILES) + yloc;
				RoutingTile tl = tiles.get(l);
				if (tl == null) {
					tl = new RoutingTile(xloc, yloc, config.ZOOM_TO_LOAD_TILES);
					tiles.put(l, tl);
				}
				res.add(tiles.get(l));
			}
		}
		return res;
	}
	
	public boolean checkIfMemoryLimitCritical() {
		return getCurrentEstimatedSize() > 0.9 * config.memoryLimitation;
	}
	
	public void unloadUnusedTiles() {
		float desirableSize = config.memoryLimitation * 0.7f;
		List<RoutingSubregionTile> list = new ArrayList<RoutingSubregionTile>(subregionTiles.size() / 2);
		int loaded = 0;
		for(RoutingSubregionTile t : subregionTiles) {
			if(t.isLoaded()) {
				list.add(t);
				loaded++;
			}
		}
		maxLoadedTiles = Math.max(maxLoadedTiles, getCurrentlyLoadedTiles());
		Collections.sort(list, new Comparator<RoutingSubregionTile>() {
			private int pow(int base, int pw) {
				int r = 1;
				for (int i = 0; i < pw; i++) {
					r *= base;
				}
				return r;
			}
			@Override
			public int compare(RoutingSubregionTile o1, RoutingSubregionTile o2) {
				int v1 = (o1.access + 1) * pow(10, o1.getUnloadCont() -1);
				int v2 = (o2.access + 1) * pow(10, o2.getUnloadCont() -1);
				return v1 < v2 ? -1 : (v1 == v2 ? 0 : 1);
			}
		});
		int i = 0;
		while(getCurrentEstimatedSize() >= desirableSize && (list.size() - i) > loaded / 5 && i < list.size()) {
			RoutingSubregionTile unload = list.get(i);
			i++;
//			System.out.println("Unload " + unload);
			unload.unload();
			// tile could be cleaned from routing tiles and deleted from whole list
			// List<RoutingTile> ts = getRoutingTiles(tile.subregion.left, tile.subregion.top, tile.subregion.right, tile.subregion.bottom);
			
		}
		for(RoutingSubregionTile t : subregionTiles) {
			t.access /= 3;
		}
	}
	
	private static long runGCUsedMemory()  {
		Runtime runtime = Runtime.getRuntime();
		long usedMem1 = runtime.totalMemory() - runtime.freeMemory();
		long usedMem2 = Long.MAX_VALUE;
		int cnt = 4;
		while (cnt-- >= 0) {
			for (int i = 0; (usedMem1 < usedMem2) && (i < 1000); ++i) {
				runtime.runFinalization();
				runtime.gc();
				Thread.yield();

				usedMem2 = usedMem1;
				usedMem1 = runtime.totalMemory() - runtime.freeMemory();
			}
		}
		return usedMem1;
	}
	


	protected NativeRouteSearchResult nativeLoadRegion(SearchRequest<RouteDataObject> request, RouteRegion reg, NativeLibrary nativeLib, boolean loadData) {
		boolean intersects = false;
		for(RouteSubregion sub : reg.getSubregions()) {
			if(request.intersects(sub.left, sub.top, sub.right, sub.bottom)) {
				intersects = true;
				break;
			}
		}
		if(intersects) {
			return nativeLib.loadRouteRegion(reg, request.getLeft(), request.getRight(), request.getTop(), request.getBottom(), loadData);
		}
		return null;
	}
	
	public static class RoutingSubregionTile {
		public final RouteSubregion subregion;
		// make it without get/set for fast access
		public int access;
		public TileStatistics tileStatistics = new TileStatistics();
		
		private NativeRouteSearchResult searchResult = null;
		private int isLoaded = 0;
		private TLongObjectMap<RouteSegment> routes = null;

		public RoutingSubregionTile(RouteSubregion subregion) {
			this.subregion = subregion;
		}
		
		private void loadAllObjects(final List<RouteDataObject> toFillIn, RoutingContext ctx) {
			if(routes != null) {
				Iterator<RouteSegment> it = routes.valueCollection().iterator();
				while(it.hasNext()){
					RouteSegment rs = it.next();
					while(rs != null){
						RouteDataObject ro = rs.road;
						if (!ctx.excludeDuplications.contains(ro.id)) {
							ctx.excludeDuplications.put(ro.id, ro);
							toFillIn.add(ro);
						}
						rs = rs.next;
					}
				}
			} else if(searchResult != null) {
				throw new UnsupportedOperationException();
			}
		}
		
		private RouteSegment loadRouteSegment(int x31, int y31, RoutingContext ctx, 
				TLongObjectHashMap<RouteDataObject> excludeDuplications, RouteSegment original) {
			if(searchResult == null && routes == null) {
				return original;
			}
			access++;
			if (searchResult == null) {
				long l = (((long) x31) << 31) + (long) y31;
				RouteSegment segment = routes.get(l);
				while (segment != null) {
					RouteDataObject ro = segment.road;
					RouteDataObject toCmp = excludeDuplications.get(ro.id);
					if (toCmp == null || toCmp.getPointsLength() < ro.getPointsLength()) {
						excludeDuplications.put(ro.id, ro);
						RouteSegment s = new RouteSegment(ro, segment.getSegmentStart());
						s.next = original;
						original = s;
					}
					segment = segment.next;
				}
				return original;
			}
			// Native use case
			RouteDataObject[] res = ctx.nativeLib.getDataObjects(searchResult, x31, y31);
			if (res != null) {
				for (RouteDataObject ro : res) {
					RouteDataObject toCmp = excludeDuplications.get(ro.id);
					boolean accept = ro != null && (toCmp == null || toCmp.getPointsLength() < ro.getPointsLength());
					if (ctx != null && accept) {
						accept = ctx.getRouter().acceptLine(ro);
					}
					if (accept) {
						excludeDuplications.put(ro.id, ro);
						for (int i = 0; i < ro.pointsX.length; i++) {
							if (ro.getPoint31XTile(i) == x31 && ro.getPoint31YTile(i) == y31) {
								RouteSegment segment = new RouteSegment(ro, i);
								segment.next = original;
								original = segment;
							}
						}
					}
				}
			}
			return original;
		}
		
		public boolean isLoaded() {
			return isLoaded > 0;
		}
		
		public int getUnloadCont(){
			return Math.abs(isLoaded);
		}
		
		public boolean isUnloaded() {
			return isLoaded < 0;
		}
		
		public void unload() {
			if(isLoaded == 0) {
				this.isLoaded = -1;	
			} else {
				isLoaded = -Math.abs(isLoaded);
			}
			if(searchResult != null) {
				searchResult.deleteNativeResult();
			}
			searchResult = null;
			routes = null;
		}
		
		public void setLoadedNonNative(){
			isLoaded = Math.abs(isLoaded) + 1;
			routes = new TLongObjectHashMap<BinaryRoutePlanner.RouteSegment>();
			tileStatistics = new TileStatistics();
		}
		
		public void add(RouteDataObject ro) {
			tileStatistics.addObject(ro);
			for (int i = 0; i < ro.pointsX.length; i++) {
				int x31 = ro.getPoint31XTile(i);
				int y31 = ro.getPoint31YTile(i);
				long l = (((long) x31) << 31) + (long) y31;
				RouteSegment segment = new RouteSegment(ro, i);
				if (!routes.containsKey(l)) {
					routes.put(l, segment);
				} else {
					RouteSegment orig = routes.get(l);
					while (orig.next != null) {
						orig = orig.next;
					}
					orig.next = segment;
				}
			}
		}
		
		public void load(NativeRouteSearchResult r) {
			isLoaded = Math.abs(isLoaded) + 1;
			searchResult = r;
			// FIXME
//			tileStatistics = new TileStatistics();
		}
	}
	
	public static class RoutingTile {
		private int tileX;
		private int tileY;
		private int zoom;
		private int isLoaded = 0;
		
		private List<RouteDataObject> routes = null;
		
		private List<RoutingSubregionTile> subregions = new ArrayList<RoutingContext.RoutingSubregionTile>(4);
		
		public RoutingTile(int tileX, int tileY, int zoom) {
			this.tileX = tileX;
			this.tileY = tileY;
			this.zoom = zoom;
		}
		
		public boolean isHeadersLoaded(){
			return isLoaded > 0;
		}
		
		public void setHeadersLoaded(){
			isLoaded = 1;
		}
		
		public boolean isEmpty(){
			return (routes == null || routes.isEmpty()) && subregions.isEmpty();
		}
	
		
		public RoutingSubregionTile searchSubregionAndAdd(RouteSubregion s, RoutingSubregionTile rt) {
			for(int i=0; i<subregions.size(); i++) {
				RoutingSubregionTile ts = subregions.get(i);
				if(ts.subregion == s){
					if(rt != null) {
						subregions.set(i, rt);
						return rt;
					}
					return ts;
				}
			}
			if(rt != null) {
				subregions.add(rt);
				return rt;
			}
			return null;
		}
		
		public void getAllObjects(final List<RouteDataObject> toFillIn, RoutingContext ctx) {
			ctx.excludeDuplications.clear();
			if (routes != null) {
				for (RouteDataObject ro : routes) {
					if (!ctx.excludeDuplications.contains(ro.id)) {
						ctx.excludeDuplications.put(ro.id, ro);
						toFillIn.add(ro);
					}
				}
			}
			for (RoutingSubregionTile rs : subregions) {
				rs.loadAllObjects(toFillIn, ctx);
			}
		}
		
		public RouteSegment getSegment(int x31, int y31, RoutingContext ctx) {
			ctx.excludeDuplications.clear();
			RouteSegment original = null;
			if (routes != null) {
				for (RouteDataObject ro : routes) {
					for (int i = 0; i < ro.pointsX.length; i++) {
						if (ro.getPoint31XTile(i) == x31 && ro.getPoint31YTile(i) == y31) {
							ctx.excludeDuplications.put(ro.id, ro);
							RouteSegment segment = new RouteSegment(ro, i);
							segment.next = original;
							original = segment;
						}
					}
				}
			}
				for (RoutingSubregionTile rs : subregions) {
					original = rs.loadRouteSegment(x31, y31, ctx, ctx.excludeDuplications, original);
				}
			return original;
		}
		
		public void registerRouteDataObject(RouteDataObject r) {
			if(routes == null){
				routes = new ArrayList<RouteDataObject>();
			}
			routes.add(r);
		}
		
		public int getId(){
			return (tileX << zoom) + tileY;
		}
		
		public int getZoom() {
			return zoom;
		}
		
		public int getTileX() {
			return tileX;
		}
		
		public int getTileY() {
			return tileY;
		}
		
		public boolean checkContains(int x31, int y31) {
			return tileX == (x31 >> (31 - zoom)) && tileY == (y31 >> (31 - zoom));
		}
		
		@Override
		public String toString() {
			return "Tile " + tileX + "/" + tileY ;
		}
	}
	
	private static class TileStatistics {
		public int size = 0;
		public int allRoutes = 0;
		public int coordinates = 0;
		
		@Override
		public String toString() {
			return "All routes " + allRoutes + 
					" size " + (size / 1024f) + " KB coordinates " + coordinates + " ratio coord " + (((float)size) / coordinates)
					+ " ratio routes " + (((float)size) / allRoutes);
		}

		public void addObject(RouteDataObject o) {
			allRoutes++;
			coordinates += o.getPointsLength() * 2;
			// calculate size
			int sz = 0;
			sz += 8 + 4; // overhead
			if (o.names != null) {
				sz += 12;
				TIntObjectIterator<String> it = o.names.iterator();
				while(it.hasNext()) {
					it.advance();
					String vl = it.value();
					sz += 12 + vl.length();
				}
				sz += 12 + o.names.size() * 25;
			}
			sz += 8; // id
			// coordinates
			sz += (8 + 4 + 4 * o.getPointsLength()) * 4;
			sz += o.types == null ? 4 : (8 + 4 + 4 * o.types.length);
			sz += o.restrictions == null ? 4 : (8 + 4 + 8 * o.restrictions.length);
			sz += 4;
			if (o.pointTypes != null) {
				sz += 8 + 4 * o.pointTypes.length;
				for (int i = 0; i < o.pointTypes.length; i++) {
					sz += 4;
					if (o.pointTypes[i] != null) {
						sz += 8 + 8 * o.pointTypes[i].length;
					}
				}
			}
			// Standard overhead?
			size += sz * 3;
			// size += coordinates * 20;
		}
	}

}