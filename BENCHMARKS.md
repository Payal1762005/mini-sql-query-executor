# Query executor benchmarks

The benchmarks use JMH 1.37 in an opt-in Maven profile. Benchmark classes live in
`src/jmh/java`; JMH is not added to the normal test or production classpath unless
the `benchmarks` profile is enabled. Synthetic CSV data is generated in a temporary
directory for each JMH trial and deleted afterward.

## Run

Build the executable benchmark jar:

```powershell
mvn -Pbenchmarks -DskipTests package
```

Run all cases with JMH defaults:

```powershell
java -Xms2g -Xmx2g -jar target\mini-sql-query-executor-1.0-SNAPSHOT-benchmarks.jar
```

For a quick smoke run with less generated data, constrain the parameters:

```powershell
java -Xms1g -Xmx1g -jar target\mini-sql-query-executor-1.0-SNAPSHOT-benchmarks.jar `
  -p rowCount=1000 -p extraColumnCount=0 -p predicateSelectivityPercent=50 `
  -p executionVariant=optimized,baseline -wi 1 -i 1 -f 1
```

To save machine-readable output, pass `-rf csv -rff target\benchmark-results.csv`.
JMH result records include average time and throughput for each benchmark and
parameter combination. The class defaults are 3 one-second warm-up iterations,
5 one-second measurement iterations, and 2 forks. JMH uses the selected JVM and
the host's CPU, memory, filesystem, and operating-system configuration.

Use JMH's GC profiler to collect allocation and garbage-collection metrics:

```powershell
java -Xms1g -Xmx1g -jar target\mini-sql-query-executor-1.0-SNAPSHOT-benchmarks.jar `
  '.*selectiveFilter' -p rowCount=1000 -p extraColumnCount=16 `
  -p predicateSelectivityPercent=50 -p dataDistribution=uniform `
  -p executionVariant=optimized,baseline -prof gc
```

## Parameters and variants

Datasets use deterministic sequential IDs, names, ten departments, and salaries.
The configured predicate selectivity is exact at the requested percentage for
the `salary > 90000` comparison. `extraColumnCount` controls width beyond the
four base fields (`id`, `name`, `department`, `salary`); use it to observe the
effect of scanning and materializing unused columns.
`dataDistribution=uniform` spreads matching rows deterministically across the
file, while `dataDistribution=clustered` places all matches in the initial
contiguous portion. Both distributions use the same requested selectivity.

The `executionVariant` parameter provides these comparisons:

| Variant | Projection pushdown | Predicate pushdown |
|---|---:|---:|
| `optimized` | yes | yes |
| `projection-only` | yes | no; comparisons use the `TableStorage` compatibility adapter |
| `predicate-only` | no; all CSV columns are materialized | yes |
| `baseline` | no; all CSV columns are materialized | no; comparisons use the compatibility adapter |

Benchmark methods execute the supported SQL end to end using
`DefaultQueryEngine`; results are materialized because that is the current
`QueryResult` API. Cases are full row scan (`SELECT *`), narrow projection
(`SELECT name`), selective filter, filter plus projection, and grouped average.
“Selective filter” and “filter plus projection” currently use the same SQL
(`SELECT name FROM employees WHERE salary > 90000`) and are intentionally retained
as separate requested labels; their measurements should therefore be equivalent
apart from normal run-to-run variance.

`fullRowScan`, `narrowProjection`, `selectiveFilter`, `filterAndProjection`, and
`groupedAverage` return output row counts to JMH. The benchmark class configures
milliseconds as its output time unit, so throughput is reported as operations per
millisecond (`ops/ms`); multiply by 1,000 to convert to queries per second.
Estimate input rows per second by multiplying queries per second by the selected
dataset row count; this is a derived scan-rate estimate, not the number of rows
returned by filtered queries. Average time is the JMH `avgt` result in `ms/op`.

## Limitations

These are end-to-end executor benchmarks, including parsing, schema lookup,
planning, CSV scanning, and result materialization. The baseline variants use
the existing `TableStorage` compatibility defaults and preserve the same SQL
results; they are not historical measurements from before the optimizations.
CSV still has to be read and parsed sequentially, so predicate pushdown is not
expected to reduce file bytes read. No speedup is asserted without running JMH
on the target environment. Allocation profiling is available through JMH's
profiler options when supported by the host/JVM (for example `-prof gc`).

Results vary with JVM version and flags, heap size, garbage collector, CPU
frequency/load, storage cache state, filesystem, operating system, and competing
work. Compare variants on the same host and JVM, retain the full JMH output, and
avoid treating a single fork or smoke run as a performance conclusion.

## Preliminary smoke measurement

A short JDK 21.0.10 run used 1,000 rows, 16 extra columns, 50% uniform
selectivity, a 1 GiB fixed initial and maximum heap, one warm-up iteration, two
measurement iterations, one fork, and `-prof gc`. It measured only
`selectiveFilter` for `optimized` and `baseline`:

| Variant | Average time | Throughput | Normalized allocation |
|---|---:|---:|---:|
| `optimized` | 3.751 ms/op | 0.250 ops/ms | 2,125,214 B/op |
| `baseline` | 4.514 ms/op | 0.186 ops/ms | 4,473,371 B/op |

These are preliminary smoke results with very limited iterations, not a
definitive performance claim. The generated benchmark CSV is under `target`
when the documented `-rff` option is used; generated data files themselves are
temporary and deleted by the benchmark state teardown.
