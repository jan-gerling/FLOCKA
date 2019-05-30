FROM java:8

RUN /usr/bin/apt-get update && \
    /usr/bin/apt-get clean && \
    /usr/sbin/locale-gen en_US en_US.UTF-8 && \
    /usr/sbin/useradd -r -s /bin/false -d /home/wdm wdm

COPY target/scala-2.12/flocka-assembly-1.0.jar /home/wdm/lib

USER wdm
ENTRYPOINT java -jar /home/wdm/lib/flocka-assembly-1.0.jar