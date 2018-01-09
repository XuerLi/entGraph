package graph;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;

import edu.stanford.nlp.util.CollectionUtils;

public class LabelPropagateMN implements Runnable {

	List<PGraph> allpGraphs;
	List<PGraph> thispGraphs;
	int runIdx;// 0: propagate, 1: average
	int threadIdx;

	public LabelPropagateMN(List<PGraph> allpGraphs, int threadIdx, int numThreads, int runIdx) {
		if (runIdx == 0) {
			this.allpGraphs = allpGraphs;
		}
		thispGraphs = new ArrayList<>();
		for (int i = 0; i < allpGraphs.size(); i++) {
			if (i % numThreads == threadIdx) {
				thispGraphs.add(allpGraphs.get(i));
			}
		}
		this.runIdx = runIdx;
		this.threadIdx = threadIdx;
	}

	void propagateLabel() {
		
		Map<String, Set<Integer>> rawPred2PGraphs = TypePropagateMN.rawPred2PGraphs;

		// r => rp is used to update p=>q. propagate similarities of pgraph to all its
		// neighbors
		for (PGraph pgraph : thispGraphs) {
			System.out.println("MN prop: " + pgraph.fname + " " + threadIdx);
			DefaultDirectedWeightedGraph<Integer, DefaultWeightedEdge> gPrev = pgraph.g0;
			
			for (int r = 0; r < gPrev.vertexSet().size(); r++) {

				if (r % 100 == 0) {
					System.out.println("r: " + r);
					TypePropagateMN.memStat();
				}

				// graphs having r's pred
				String pred_r = pgraph.idx2node.get(r).id;
				String[] ss_r = pred_r.split("#");
				String t1_r_plain = ss_r[1].replace("_1", "").replace("_2", "");
				String t2_r_plain = ss_r[2].replace("_1", "").replace("_2", "");

				String rawPred_r = ss_r[0];// the raw predicate: e.g., (visit.1,visit.2)
				Set<Integer> rawPred_r_PGraphs = rawPred2PGraphs.get(rawPred_r); // pgraphs with this predicate

				// Let's compute sum of coef*sim
				for (DefaultWeightedEdge e : gPrev.outgoingEdgesOf(r)) {
					int rp = gPrev.getEdgeTarget(e);

					// Let's get all the neighbors
					// graphs having rp's pred
					String pred_rp = pgraph.idx2node.get(rp).id;
					String[] ss_rp = pred_rp.split("#");
					String rawPred_rp = ss_rp[0];// the raw predicate: e.g., // (visit.1,visit.2)
					boolean aligned = ss_r[1].equals(ss_rp[1]);
					Set<Integer> rawPred_rp_PGraphs = rawPred2PGraphs.get(rawPred_rp); // pgraphs with this predicate

					Set<Integer> neighborGraphs = CollectionUtils.intersection(rawPred_r_PGraphs, rawPred_rp_PGraphs);

					double sim = gPrev.getEdgeWeight(e);
					int minPairOcc1 = 1;
					if (TypePropagateMN.sizeBasedPropagation) {
						minPairOcc1 = Math.min(TypePropagateMN.predToOcc.get(pred_r),
								TypePropagateMN.predToOcc.get(pred_rp));
					}

					// Let's propagate to all the neighbor graphs
					//neighGraphs have the rawPred, but we don't know about the exact type ordering.
					//So, we try both ways!
					for (int ngIdx : neighborGraphs) {
						PGraph pgraph_neigh = allpGraphs.get(ngIdx);
						String tp1 = pgraph_neigh.types.split("#")[0];// don't get confused with rp, etc. tp1 is for the
																		// neigh graph
						String tp2 = pgraph_neigh.types.split("#")[1];
						String tp1_plain = tp1;
						String tp2_plain = tp2;

						if (tp1.equals(tp2)) {
							tp1 += "_1";
							tp2 += "_2";
						}

						// case 1: tp1 and tp2
						String pred_p = rawPred_r + "#" + tp1 + "#" + tp2;
						String pred_q;

						if (aligned) {
							pred_q = rawPred_rp + "#" + tp1 + "#" + tp2;
						} else {
							pred_q = rawPred_rp + "#" + tp2 + "#" + tp1;
						}

						double compScore1, compScore2;

						if (!TypePropagateMN.predBasedPropagation) {
							// make sure you give to both cases: tp1#tp2 and tp2#tp1
							compScore1 = TypePropagateMN.getCompatibleScore(tp1_plain, tp2_plain, aligned, t1_r_plain, t2_r_plain);
							// compScore1 *= Math.min(pgraph.nodes.size(), pgraph_neigh.nodes.size());//
							// TODO: added, be
							// careful

						} else {
//							System.out.println("from label prop1");
							// make sure you give to both cases: tp1#tp2 and tp2#tp1
							//how much pred_r => pred_rp is compatible with pred_p => pred_q
							//pred_r(p) are in pgraph. pred_p(q) are in pgraph_neigh
							//types are t1_r, t2_r and tp1, tp2
							compScore1 = TypePropagateMN.getCompatibleScorePredBased(pgraph, pgraph_neigh, rawPred_r,
									rawPred_rp, pred_r, pred_rp, pred_p, pred_q, t1_r_plain, t2_r_plain, aligned, tp1_plain,
									tp2_plain);
							// compScore1 *= Math.min(pgraph.nodes.size(), pgraph_neigh.nodes.size());//
							// TODO: added, be
							// careful

						}
						
//						System.out.println("propagating from: "+pred_r+" "+pred_rp);
						// propagate similarity to pred_p => pred_q. Also, compute the coef sum of all
						// the neighbors for those (pre-compute all at once)
						propagateOneEdge(pgraph, pgraph_neigh, pred_p, pred_q, sim, compScore1, rawPred_r, rawPred_rp,
								tp1_plain, tp2_plain, aligned, neighborGraphs, minPairOcc1);

						// case 2: tp2 and tp1
						pred_p = rawPred_r + "#" + tp2 + "#" + tp1;

						if (aligned) {
							pred_q = rawPred_rp + "#" + tp2 + "#" + tp1;
						} else {
							pred_q = rawPred_rp + "#" + tp1 + "#" + tp2;
						}

						if (!TypePropagateMN.predBasedPropagation) {
							compScore2 = TypePropagateMN.getCompatibleScore(tp2_plain, tp1_plain, aligned, t1_r_plain, t2_r_plain);
							// compScore2 *= Math.min(pgraph.nodes.size(), pgraph_neigh.nodes.size());//
							// TODO: added, be
							// careful
						} else {
//							System.out.println("from label prop2");
							compScore2 = TypePropagateMN.getCompatibleScorePredBased(pgraph, pgraph_neigh, rawPred_r,
									rawPred_rp, pred_r, pred_rp, pred_p, pred_q, t1_r_plain, t2_r_plain, aligned, tp2_plain,
									tp1_plain);
							// compScore2 *= Math.min(pgraph.nodes.size(), pgraph_neigh.nodes.size());//
							// TODO: added, be
							// careful
						}

						propagateOneEdge(pgraph, pgraph_neigh, pred_p, pred_q, sim, compScore2, rawPred_r, rawPred_rp,
								tp2_plain, tp1_plain, aligned, neighborGraphs, minPairOcc1);

					}
				}
			}
			// pgraph.g0 = null;//TODO: make this null? need it for distance
			System.out.println("all prop edges: " + TypePropagateMN.allPropEdges);
		}
		System.out.println("thread Idx +" + threadIdx + " done");
	}

	// private static String removeUnderlines(String t) {
	// return t.replace("_1", "").replace("_2", "");
	// }

	// propagate for one edge (r => rp) from pgraph to pgraph_neigh (pred_p=>pred_q)
	void propagateOneEdge(PGraph pgraph, PGraph pgraph_neigh, String pred_p, String pred_q, double sim,
			double compScore, String rawPred_p, String rawPred_q, String tp1, String tp2, boolean aligned,
			Set<Integer> neighborGraphs, int minPairOcc1) {
		if (pgraph_neigh.pred2node.containsKey(pred_p) && pgraph_neigh.pred2node.containsKey(pred_q)) {
//			System.out.println("propagating from graph: " + pgraph.types + " to graph " + pgraph_neigh.types + " for "
//					+ pred_p + " " + pred_q+" "+compScore);
			int minPairOcc2 = 1;
			if (TypePropagateMN.sizeBasedPropagation) {
				minPairOcc2 = Math.min(TypePropagateMN.predToOcc.get(pred_p), TypePropagateMN.predToOcc.get(pred_q));
				compScore *= Math.min(minPairOcc1, minPairOcc2);
			}

			DefaultDirectedWeightedGraph<Integer, DefaultWeightedEdge> gMN = pgraph_neigh.gMN;
			int p = pgraph_neigh.pred2node.get(pred_p).idx;
			int q = pgraph_neigh.pred2node.get(pred_q).idx;

			double w;
			DefaultWeightedEdge ee;
			String edgeStr = p + "#" + q;
			synchronized (gMN) {

				if (!gMN.containsEdge(p, q)) {
					ee = gMN.addEdge(p, q);
					gMN.setEdgeWeight(ee, 0);
					w = 0;
					TypePropagateMN.allPropEdges++;
				} else {
					ee = gMN.getEdge(p, q);
					w = gMN.getEdgeWeight(ee);
				}

				gMN.setEdgeWeight(ee, w + sim * compScore);

			}

			synchronized (pgraph_neigh.edgeToMNWeight) {
				if (!pgraph_neigh.edgeToMNWeight.containsKey(edgeStr)) {

					double sumCoefs = getSumNeighboringCoefs(pgraph_neigh, rawPred_p, rawPred_q, pred_p, pred_q, tp1,
							tp2, aligned, neighborGraphs, minPairOcc2);
					pgraph_neigh.edgeToMNWeight.put(edgeStr, sumCoefs);
				}

			}

		}
	}

	// rawPred_p#rawPred_q#aligned#tp1#tp2 will receive message from neighGraphs
	double getSumNeighboringCoefs(PGraph pgraph, String rawPred_p, String rawPred_q, String pred_p, String pred_q,
			String tp1, String tp2, boolean aligned, Set<Integer> neighborGraphs, int minPairOcc2) {

		double sumCoefs = 0;
		for (int gIdx : neighborGraphs) {
			PGraph pgraph_neigh = allpGraphs.get(gIdx);
			String ss[] = pgraph_neigh.types.split("#");
			String t1 = ss[0];
			String t2 = ss[1];
			String t1_plain = t1;
			String t2_plain = t2;

			if (t1.equals(t2)) {
				t1 += "_1";
				t2 += "_2";
			}

			String p1 = rawPred_p + "#" + t1 + "#" + t2;
			String p2 = rawPred_p + "#" + t2 + "#" + t1;
			String q1, q2;

			if (aligned) {
				q1 = rawPred_q + "#" + t1 + "#" + t2;
				q2 = rawPred_q + "#" + t2 + "#" + t1;
			} else {
				q1 = rawPred_q + "#" + t2 + "#" + t1;
				q2 = rawPred_q + "#" + t1 + "#" + t2;
			}

			double compScore1;
			double compScore2;

			// make sure you get from both cases: t1#t2 and t2#t1
			if (!TypePropagateMN.predBasedPropagation) {
				compScore1 = TypePropagateMN.getCompatibleScore(tp1, tp2, aligned, t1_plain, t2_plain);
				// compScore1 *= Math.min(pgraph.nodes.size(), pgraph_neigh.nodes.size());//
				// TODO: added, be careful

				compScore2 = TypePropagateMN.getCompatibleScore(tp1, tp2, aligned, t2_plain, t1_plain);
				// compScore2 *= Math.min(pgraph.nodes.size(), pgraph_neigh.nodes.size());//
				// TODO: added, be careful
			} else {

				// It should be originally propagating from pred_r=>pred_rp to pred_rp=>pred_q
				// but here, we do from pred_p=>pred_q to p1, q1. Doesn't matter!
				compScore1 = TypePropagateMN.getCompatibleScorePredBased(pgraph, pgraph_neigh, rawPred_p, rawPred_q,
						pred_p, pred_q, p1, q1, tp1, tp2, aligned, t1_plain, t2_plain);
				compScore2 = TypePropagateMN.getCompatibleScorePredBased(pgraph, pgraph_neigh, rawPred_p, rawPred_q,
						pred_p, pred_q, p2, q2, tp1, tp2, aligned, t2_plain, t1_plain);

			}

			if (pgraph_neigh.pred2node.containsKey(p1) && pgraph_neigh.pred2node.containsKey(q1)) {
				// System.out.println("propagating: "+rawPred_p+" "+rawPred_q+" "+tp1+" "+tp2+"
				// "+aligned+" "+t1+" "+t2+" "+compScore1);

				if (TypePropagateMN.sizeBasedPropagation) {
					int minPairOcc1 = Math.min(TypePropagateMN.predToOcc.get(p1), TypePropagateMN.predToOcc.get(q1));
					compScore1 *= Math.min(minPairOcc1, minPairOcc2);
				}
				sumCoefs += compScore1;
			}
			if (pgraph_neigh.pred2node.containsKey(p2) && pgraph_neigh.pred2node.containsKey(q2)) {
				// System.out.println("propagating: "+rawPred_p+" "+rawPred_q+" "+tp1+" "+tp2+"
				// "+aligned+" "+t2+" "+t1+" "+compScore2);
				if (TypePropagateMN.sizeBasedPropagation) {
					int minPairOcc1 = Math.min(TypePropagateMN.predToOcc.get(p2), TypePropagateMN.predToOcc.get(q2));
					compScore2 *= Math.min(minPairOcc1, minPairOcc2);
				}

				sumCoefs += compScore2;
			}
		}
		return sumCoefs;
	}

	void writeTPropResults(PGraph pgraph, List<DefaultDirectedWeightedGraph<Integer, DefaultWeightedEdge>> gs,
			String fnameTProp) {
		// list of all predicates can be found from the last graph. The indexes
		// are also the same (if existing) with previous graphs
		PrintStream op = null;
		try {
			op = new PrintStream(new File(fnameTProp));
		} catch (Exception e) {
		}
		// List<String> predList = allPredsList.get(allPredsList.size() - 1);

		int N = pgraph.idx2node.size();
		op.println(pgraph.name + " " + " type propagation num preds: " + N);

		DefaultDirectedWeightedGraph<Integer, DefaultWeightedEdge> lastG = gs.get(gs.size() - 1);
		for (int i = 0; i < N; i++) {
			String pred = pgraph.idx2node.get(i).id;
			op.println("predicate: " + pred);
			op.println("num max neighbors: " + lastG.outgoingEdgesOf(i).size());
			op.println();
			for (int iter = 0; iter < gs.size(); iter++) {
				DefaultDirectedWeightedGraph<Integer, DefaultWeightedEdge> thisG = gs.get(iter);
				op.println("iter " + iter + " sims");
				List<SimpleScore> scores = new ArrayList<>();
				if (thisG.containsVertex(i)) {
					for (DefaultWeightedEdge e : thisG.outgoingEdgesOf(i)) {
						int j = thisG.getEdgeTarget(e);
						String pred2 = pgraph.idx2node.get(j).id;
						double w = thisG.getEdgeWeight(e);
						scores.add(new SimpleScore("", pred2, (float) w));
					}

					Collections.sort(scores, Collections.reverseOrder());
					for (SimpleScore sc : scores) {
						op.println(sc.pred2 + " " + sc.score);
					}
				}
				op.println();
			}
		}
		op.close();
	}

	void getAvg() {

		for (PGraph pgraph : thispGraphs) {
			// Get the average
			DefaultDirectedWeightedGraph<Integer, DefaultWeightedEdge> gMN = pgraph.gMN;
			int curN = gMN.vertexSet().size();
			List<DefaultWeightedEdge> removableEdges = new ArrayList<>();
			for (int p = 0; p < curN; p++) {
				for (DefaultWeightedEdge e : gMN.outgoingEdgesOf(p)) {
					int q = gMN.getEdgeTarget(e);
					if (p == q) {
						gMN.setEdgeWeight(e, 1);
					} else {
						double denom = pgraph.edgeToMNWeight.get(p + "#" + q);
						double c = gMN.getEdgeWeight(e);

						if (c <= TypePropagateMN.lmbda && c >= -TypePropagateMN.lmbda) {
							removableEdges.add(e);
							continue;
						} else {
							double w;
							if (c > 0) {
								w = (c - TypePropagateMN.lmbda) / denom;
							} else {
								w = (c + TypePropagateMN.lmbda) / denom;
							}
							// System.out.println(
							// "avg: " + pgraph.idx2node.get(p).id + " " + pgraph.idx2node.get(q).id + " ");
							// System.out.println("avg: " + w + " " + gMN.getEdgeWeight(e) + " " + denom);

							if (w > 1.01) {
								System.out.println("bug: " + w + " " + gMN.getEdgeWeight(e) + " " + denom);
								System.out.println(pgraph.nodes.get(p).id + " " + pgraph.nodes.get(q).id + " ");
							}
							gMN.setEdgeWeight(e, w);

							double w0 = 0;
							if (pgraph.g0.containsEdge(p, q)) {
								DefaultWeightedEdge e0 = pgraph.g0.getEdge(p, q);
								w0 = pgraph.g0.getEdgeWeight(e0);
							}

							TypePropagateMN.objChange += Math.pow(w - w0, 2);

						}

						//
					}
				}
			}

			gMN.removeAllEdges(removableEdges);// TODO: you can do better here, by changing the order of the stuff

			// now, g0 is null, gMN is the next one

		}

	}

	// write the output of all of thispGraphs
	void writeResults() {
		while (thispGraphs.size() > 0) {
			PGraph pgraph = thispGraphs.remove(0);

			List<DefaultDirectedWeightedGraph<Integer, DefaultWeightedEdge>> gs = new ArrayList<>();
			pgraph.g0 = pgraph.formWeightedGraph(pgraph.sortedEdges, pgraph.nodes.size());
			gs.add(pgraph.g0);
			gs.add(pgraph.gMN);

			String fnameTProp = pgraph.fname.substring(0, pgraph.fname.lastIndexOf('_')) + TypePropagateMN.tPropSuffix;
			writeTPropResults(pgraph, gs, fnameTProp);
		}
	}

	@Override
	public void run() {
		if (runIdx == 0) {
			propagateLabel();
			allpGraphs = null;
		} else if (runIdx == 1) {
			getAvg();
		} else {
			writeResults();
		}

	}

}
