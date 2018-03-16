package graph;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.jgrapht.alg.ConnectivityInspector;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;

//Gets a pgraph and a lambda, runs ILP, returns transitive graph!

public class LinProgTransGraphBuilder {
	PGraph pgraph;
	double lmbda;
	gtGraph scc;
	int[] node2comp;

	public LinProgTransGraphBuilder(PGraph pgraph, double lmbda) {
		this.lmbda = lmbda;
		this.pgraph = pgraph;
	}

	public LinProgTransGraphBuilder(String fname, double lmbda) {
		this.lmbda = lmbda;
		this.pgraph = new PGraph(PGraph.root + fname);
	}

	gtGraph findTransGraph() {

		int N = pgraph.nodes.size();
		System.err.println("N: "+N);

		this.node2comp = new int[N];
		gtGraph scc = new gtGraph(DefaultEdge.class);

		for (int i = 0; i < N; i++) {
			scc.addVertex(i);
			List<Integer> l = new ArrayList<>();
			l.add(i);
			scc.comps.add(l);
			node2comp[i] = i;
		}

		List<List<Integer>> cc = findComponents(pgraph);

		for (List<Integer> c : cc) {
			System.out.println("component: " + c.size() + " elements");
			// for (int x:c) {
			// System.out.print(x+" ");
			// }
			LinProgCplex lp = new LinProgCplex(pgraph, lmbda, c);
			double[] sol = lp.solveILPIncremental();
			int Nc = c.size();
			for (int idx = 0; idx < sol.length; idx++) {
				if (sol[idx] == 1.0) {
					int i0 = idx / Nc;
					int j0 = idx % Nc;

					int i = c.get(i0);
					int j = c.get(j0);

					scc.addEdge(i, j);

					// System.out.println(pgraph.nodes.get(idxes.get(i)).id + "=>" +
					// pgraph.nodes.get(idxes.get(j)).id);
					// System.out.println(idxes.get(i) + "=>" + idxes.get(j));
				}
			}

		}

		this.scc = TransClUtils.updateSCC(scc, node2comp);
		return scc;

	}

	List<List<Integer>> findComponents(PGraph pgraph) {
		SimpleGraph<Integer, DefaultEdge> sg = new SimpleGraph<>(DefaultEdge.class);
		for (int i = 0; i < pgraph.nodes.size(); i++) {
			sg.addVertex(i);
		}

		for (int i = 0; i < pgraph.nodes.size(); i++) {
			for (Oedge e : pgraph.nodes.get(i).oedges) {
				if (e.sim >= lmbda && i != e.nIdx) {
					sg.addEdge(i, e.nIdx);
				}
			}
		}

		ConnectivityInspector<Integer, DefaultEdge> ci = new ConnectivityInspector<>(sg);
		List<Set<Integer>> connectedSets = ci.connectedSets();
		List<List<Integer>> ret = new ArrayList<>();
		for (Set<Integer> c : connectedSets) {
			List<Integer> l = new ArrayList<>();
			for (int x : c) {
				l.add(x);
			}
			ret.add(l);
		}
		return ret;
	}

	public static void main(String[] args) {

		File folder = new File(PGraph.root);
		File[] files = folder.listFiles();
		Arrays.sort(files);

		for (File f : files) {
			String fname = f.getName();
			if (!fname.contains("broadcast_network#thing")) {
				continue;
			}
			// if (fname.startsWith("location#location_sim.txt")) {
			// seenLoc = true;
			// }
			// if (seenLoc) {
			// break;
			// }

			if (!fname.contains(PGraph.suffix)) {
				continue;
			}

			// if (gc++==50) {
			// break;
			// }

			System.out.println("fname: " + fname);
			LinProgTransGraphBuilder lpRunner = new LinProgTransGraphBuilder(fname, .2);
			lpRunner.findTransGraph();
			// LinProg linProg = new LinProg(pgraph, .2);
			// linProg.solveILPIncremental();

		}
	}

}
