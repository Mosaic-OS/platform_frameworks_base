package vendor.google.google_battery;

interface IGoogleBattery {
    @Backing(type="int")
    enum BatteryChargingPolicy {
        DEFAULT = 1,
        LONGLIFE = 2,
        ADAPTIVE = 3,
    }
    int getProperty(int feature, int property) = 4;
    void setProperty(int feature, int property, int value) = 5;

    void setChargingPolicy(BatteryChargingPolicy policy) = 22;

    String getStringProperty(int feature, int prop) = 23;
}
