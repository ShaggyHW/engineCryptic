use navpath_core::eligibility::EligibilityMask;
use navpath_core::NeighborProvider;

#[test]
fn excluded_edges_leave_the_filter_by_snapshot_index() {
    // Edges given out of source order, so CSR slots differ from snapshot indices.
    let (src, dst, w) = ([2u32, 0, 1, 0], [3u32, 1, 2, 2], [10.0f32, 20.0, 30.0, 40.0]);
    let reqs = vec![Vec::new(); 4];
    for provider in [
        NeighborProvider::new_with_reqs(4, &src, &dst, &w, &[0; 4], &reqs),
        NeighborProvider::new_with_reqs(4, &dst, &src, &w, &[0; 4], &reqs),
    ] {
        let mask = EligibilityMask { satisfied: Vec::new() };
        let mut filter = provider.macro_filter(&mask, false);
        provider.exclude_edges(&mut filter, |edge| edge == 3);
        let mut kept: Vec<f32> =
            (0..4u32).flat_map(|u| provider.macro_neighbors(u, Some(&filter)).map(|(_, w)| w).collect::<Vec<_>>()).collect();
        kept.sort_by(f32::total_cmp);
        assert_eq!(kept, vec![10.0, 20.0, 30.0]);
    }
}
