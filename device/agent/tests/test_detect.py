from dekoi_agent.detect import COLLAPSE_SECONDS, Collapser, ScanDetector

T = 1_800_000_000.0


def test_five_ports_from_one_source_is_a_scan() -> None:
    detector = ScanDetector()
    results = [detector.observe(T + i, "10.0.0.9", "aa:bb:cc:00:00:01", 1000 + i) for i in range(5)]
    assert results[:4] == [None] * 4
    scan = results[4]
    assert scan is not None
    assert scan["kind"] == "port_scan"
    assert scan["count"] == 5
    assert scan["detail"] == {"ports": [1000, 1001, 1002, 1003, 1004]}
    assert scan["src_mac"] == "aa:bb:cc:00:00:01"


def test_slow_probes_are_not_a_scan() -> None:
    detector = ScanDetector()
    for i in range(5):
        detector.expire(T + i * 61)
        assert detector.observe(T + i * 61, "10.0.0.9", None, 1000 + i) is None


def test_repeated_probes_of_one_port_are_not_a_scan() -> None:
    detector = ScanDetector()
    assert all(detector.observe(T + i, "10.0.0.9", None, 445) is None for i in range(20))


def test_first_event_passes_and_repeats_are_counted() -> None:
    collapser = Collapser()
    event = {"kind": "login_attempt", "service": "ssh", "src_ip": "10.0.0.9"}
    assert collapser.observe(T, event) == event
    for i in range(1, 10):
        assert collapser.observe(T + i, event) is None
    assert collapser.flush(T + COLLAPSE_SECONDS - 1) == []
    (summary,) = collapser.flush(T + COLLAPSE_SECONDS)
    assert summary["count"] == 9
    assert summary["kind"] == "login_attempt"
    assert collapser.observe(T + COLLAPSE_SECONDS + 1, event) == event


def test_different_sources_are_not_collapsed() -> None:
    collapser = Collapser()
    first = {"kind": "connection", "service": "http", "src_ip": "10.0.0.9"}
    second = first | {"src_ip": "10.0.0.10"}
    assert collapser.observe(T, first) == first
    assert collapser.observe(T, second) == second


def test_a_single_event_leaves_nothing_to_flush() -> None:
    collapser = Collapser()
    collapser.observe(T, {"kind": "connection", "service": "http", "src_ip": "10.0.0.9"})
    assert collapser.flush(T + COLLAPSE_SECONDS) == []
