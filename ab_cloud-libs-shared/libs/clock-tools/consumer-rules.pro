# libs:clock-tools: the host is named in the manifest (meta-data com.diegonmarcos.clocktools.HOST) and made by reflection.
-keep class * extends com.diegonmarcos.clocktools.ClockHost { <init>(); }
