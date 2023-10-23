myFile = file.choose() #ChapterFiles2ndEdition/ch2/u01data.txt
myFile
data = read.table(myFile)
b = seq(0,1, by = 0.1)
h = hist(data$V1, b, right = FALSE)
chisq.test(h$counts)

