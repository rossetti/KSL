myFile = file.choose() #example6-2Data.txt
packageCnt = scan(myFile)
hist(packageCnt, main="Packages per Shipment", xlab="#packages")
stripchart(packageCnt,method="stack",pch="o")
table(packageCnt)
summary(packageCnt)
tp = table(packageCnt)
tp/40

