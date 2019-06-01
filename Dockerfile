FROM java:8

RUN  /usr/sbin/useradd -r -s /bin/false -d /home/wdm wdm

COPY target/scala-2.12/flocka-assembly-1.0.jar /home/wdm/lib/runner.jar
COPY run.sh /home/wdm/run.sh 

USER wdm

ENTRYPOINT ["/home/wdm/run.sh"]
