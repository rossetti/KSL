# M/M/1 Queue — Example Results


## Simulation Summary

**Experiment Configuration**


|Property| Value|
|:---| :---|
|Simulation Name| MM1|
|Model Name| MM1|
|Experiment Name| Experiment_1|
|Replications| 30|
|Run Length| 500.0|
|Warm-Up Length| 50.0|

**Across-Replication Statistics**


|Name| Count| Average| Std Dev| Std Error| Half-Width| Conf Level| CI Lower| CI Upper| Min| Max|
|:---| ---:| ---:| ---:| ---:| ---:| ---:| ---:| ---:| ---:| ---:|
|NumBusy| 30.0000| 0.5105| 0.0314| 0.0057| 0.0117| 0.9500| 0.4988| 0.5223| 0.4361| 0.5683|
|Num in System| 30.0000| 1.0771| 0.2095| 0.0382| 0.0782| 0.9500| 0.9989| 1.1553| 0.7974| 1.7543|
|System Time| 30.0000| 1.0822| 0.1776| 0.0324| 0.0663| 0.9500| 1.0159| 1.1485| 0.8426| 1.6251|
|WaitingQ:NumInQ| 30.0000| 0.5666| 0.1870| 0.0341| 0.0698| 0.9500| 0.4968| 0.6364| 0.3613| 1.2027|
|WaitingQ:TimeInQ| 30.0000| 0.5681| 0.1680| 0.0307| 0.0627| 0.9500| 0.5054| 0.6308| 0.3811| 1.1140|
|Num Served| 30.0000| 447.8333| 19.0446| 3.4770| 7.1114| 0.9500| 440.7220| 454.9447| 409.0000| 486.0000|


## System Time — Distribution Across Replications


### System Time — replication averages

Bins: 10  |  Range: [0.000, 2.000]  |  Under: 0  |  Over: 0  |  Total: 30  |  In Bins: 30  |  Missing: 0

**Bin Frequencies**


|Bin| Label| Lower| Upper| Count| Cum Count| %| Cum %|
|---:| :---| ---:| ---:| ---:| ---:| :---| :---|
|1|   1 [ 0.00, 0.20) | 0.000| 0.2000| 0| 0| 0.00%| 0.00%|
|2|   2 [ 0.20, 0.40) | 0.2000| 0.4000| 0| 0| 0.00%| 0.00%|
|3|   3 [ 0.40, 0.60) | 0.4000| 0.6000| 0| 0| 0.00%| 0.00%|
|4|   4 [ 0.60, 0.80) | 0.6000| 0.8000| 0| 0| 0.00%| 0.00%|
|5|   5 [ 0.80, 1.00) | 0.8000| 1.000| 11| 11| 36.67%| 36.67%|
|6|   6 [ 1.00, 1.20) | 1.000| 1.200| 13| 24| 43.33%| 80.00%|
|7|   7 [ 1.20, 1.40) | 1.200| 1.400| 5| 29| 16.67%| 96.67%|
|8|   8 [ 1.40, 1.60) | 1.400| 1.600| 0| 29| 0.00%| 96.67%|
|9|   9 [ 1.60, 1.80) | 1.600| 1.800| 1| 30| 3.33%| 100.00%|
|10|  10 [ 1.80, 2.00) | 1.800| 2.000| 0| 30| 0.00%| 100.00%|

**Statistics on Binned Data**


|Property| Value|
|:---| ---:|
|Count| 30.00|
|Average| 1.082|
|Std Dev| 0.1776|
|Std Error| 0.03243|
|Half-width| 0.06632|
|Confidence Level| 0.9500|
|CI Lower| 1.016|
|CI Upper| 1.149|
|Min| 0.8426|
|Max| 1.625|
|Sum| 32.47|
|Variance| 0.03154|
|Dev Sum of Sq| 0.9147|
|Kurtosis| 1.544|
|Skewness| 1.195|
|Lag-1 Covariance| -0.007068|
|Lag-1 Correlation| -0.2318|
|Von Neumann Stat| -1.251|
|Missing| 0.000|

![System Time — replication averages](../images/single/System_Time___replication_averages.PNG)


