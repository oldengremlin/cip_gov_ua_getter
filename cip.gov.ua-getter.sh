#!/bin/sh

CUR_DIR=$( pwd )

scp ns:/etc/powerdns/blocked.txt .
scp ns:/etc/powerdns/blocked.ncu .

echo -n "Get Lists from cip.gov.ua: "
. /etc/profile.d/maven.sh
mvn -q exec:java >> cip.gov.ua-getter.out.log 2>>cip.gov.ua-getter.err.log
echo "done."

echo -n "We update the list of domains in the blocked.txt file: "
test -r blocked.result.txt && cp blocked.result.txt blocked.txt~
for D in $( diff -u blocked.ncu blocked.result.txt | egrep -v "^\+" | egrep "^ " | sed "s/^ //" ); do
    sed -i "/^"${D}"$/d" blocked.txt~
done
echo "done."

for NS in ns ns2; do
    scp blocked.txt~ ${NS}:/tmp
    ssh ${NS} "sudo mv /tmp/blocked.txt~ /etc/powerdns/blocked.txt"
    ssh ${NS} "sudo chown root.root /etc/powerdns/blocked.txt"
    ssh ${NS} "sudo /etc/init.d/pdns-recursor restart"
done

cd ${CUR_DIR}
