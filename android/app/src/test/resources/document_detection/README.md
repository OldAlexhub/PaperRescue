# Document detection regression dataset

Place licensed test images in `images/` and add one row per image to
`ground_truth.csv` using normalized, display-oriented TL/TR/BR/BL coordinates:

`filename,tl_x,tl_y,tr_x,tr_y,br_x,br_y,bl_x,bl_y,document_present`

The benchmark runner can use `DetectionBenchmarkMetrics` to report polygon IoU,
normalized corner error, success rate, wrong-object rate, manual-correction rate,
and processing latency. No images or benchmark claims are bundled because the
repository does not currently contain a licensed, ground-truthed corpus.
