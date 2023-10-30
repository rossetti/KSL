# tabulate the counts
tCnts = table(p2$N)
tCnts
# get number of observations
n = length(p2$N)
n
# estimate the rate for Poisson from the data
lamdba = mean(p2$N)
lamdba
# setup vector of x's across domain of Poisson
x = 0:15
x
# compute the probability for Poisson
prob = dpois(x, lambda)
prob
# view the expected for these probabilities
prob*n
# compute the probability for the classes
cProb = c(sum(prob[1:5]), prob[6:11], sum(prob[12:13]), ppois(12, lambda, lower.tail = FALSE))
cProb
# compute the expected for each of the classes
expected = cProb*n
expected
# transform observed counts to data frame
dfCnts = as.data.frame(tCnts)
dfCnts
# extract only frequencies
cnts = dfCnts$Freq
cnts
# consolidate classes for observed
observed = c(sum(cnts[1:4]), cnts[5:10], sum(cnts[11:12]), sum(cnts[13:16]))
observed
# compute the observed minus expected components
chisq = ((observed - expected)^2)/expected
# compute the chi-squared test statistic
sumchisq = sum(chisq)
# set the degrees of freedom, with 1 estimated parameter s = 1
df = length(expected) - 1 - 1
# compute the p-value
pvalue = 1 - pchisq(sumchisq, df)
print(sumchisq)
print(pvalue)





