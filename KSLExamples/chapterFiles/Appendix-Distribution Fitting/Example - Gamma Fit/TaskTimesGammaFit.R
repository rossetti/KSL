myFile = file.choose() #taskTimes.txt
y = scan(myFile) #read in the file
hist(y, main="Task Times", xlab = "minutes")
plot(y,type="b",main="Task Times", ylab = "minutes",xlab = "Observation#")
acf(y, main = "ACF Plot for Task Times")
summary(y)
mean(y)
var(y)
sd(y)
t.test(y)
h = hist(y)
h
br = c(0,4,8,12,16,20,24,28,32)  #define the breaks in a vector
y.cut = cut(y, breaks=br) #define the intervals
table(y.cut)
hb = hist(y, breaks = br)
a = mean(y)*mean(y)/var(y) #estimate alpha
b = var(y)/mean(y) #estmate beta
hy = hist(y, main="Task Times", xlab = "minutes") # make histogram
LL = hy$breaks # set lower limit of intervals
UL = c(LL[-1],10000) # set upper limit of intervals
FLL = pgamma(LL,shape = a, scale = b) #compute F(LL)
FUL = pgamma(UL,shape = a, scale = b)  #compute F(UL)
pj = FUL - FLL # compute prob of being in interval
ej = length(y)*pj # compute expected number in interval
e = c(ej[1:5],sum(ej[6:8])) #combine last 3 intervals
cnts = c(hy$counts[1:5],sum(hy$counts[6:7])) #combine last 3 intervals
chissq = ((cnts-e)^2)/e #compute chi sq values
sumchisq = sum(chissq) # compute test statistic
df = length(e)-2-1 #compute degrees of freedom
pvalue = 1 - pchisq(sumchisq, df) #compute p-value
print(sumchisq) # print test statistic
print(pvalue) #print p-value
j = 1:length(y) # make a vector to count y's
yj = sort(y) # sort the y's
Fj = pgamma(yj, shape = a, scale = b)  #compute F(yj)
n = length(y)
D = max(max((j/n)-Fj),max(Fj - ((j-1)/n))) # compute K-S test statistic
print(D)
ks.test(y, 'pgamma', shape=a, scale =b) # compute k-s test
plot(Fj,ppoints(length(y))) # make P-P plot
abline(0,1) # add a reference line to the plot
qqplot(y, qgamma(ppoints(length(y)), shape = a, scale = b)) # make Q-Q Plot
abline(0,1) # add a reference line to the plot
library(fitdistrplus) #use install.packages("fitdistrplus) prior if not installed
descdist(y)
fy = fitdist(y, "gamma")
print(fy)
plot(fy)
gfy = gofstat(fy)
print(gfy)
print(gfy$chisq)
print(gfy$sqbreaks)
print(gfy$chisqpvalue)
print(gfy$chisqdf)
print(gfy$chisqtable)
fymm = fitdist(y, "gamma", method="mme")
print(fymm)
plot(fymm)
gfymm = gofstat(fymm)
print(gfymm)