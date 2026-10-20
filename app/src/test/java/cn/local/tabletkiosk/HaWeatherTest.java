package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 从 Home Assistant 状态列表里挑选天气，不访问网络。 */
public class HaWeatherTest {
    /** 跳过不可用实体，优先和风，温度按原单位显示。 */
    @Test
    public void prefersHeWeatherAndKeepsTemperature() {
        String body = "["
                + "{\"entity_id\":\"weather.other\",\"state\":\"rainy\",\"attributes\":{\"temperature\":1,\"temperature_unit\":\"°C\"}},"
                + "{\"entity_id\":\"weather.offline\",\"state\":\"unavailable\",\"attributes\":{}},"
                + "{\"entity_id\":\"weather.he_feng_tian_qi_heweather_2\",\"state\":\"sunny\","
                + "\"attributes\":{\"temperature\":23,\"temperature_unit\":\"°C\"}}"
                + "]";
        HaWeather.Snapshot snapshot = HaWeather.parse(body);
        assertEquals("sunny", snapshot.state);
        assertEquals("23°C 晴", HaWeather.display(snapshot, true));
        assertEquals("23°C Sunny", HaWeather.display(snapshot, false));
    }

    /** 没有温度时不写成 0，只显示天气状况。 */
    @Test
    public void missingTemperatureIsNotZero() {
        String body = "[{\"entity_id\":\"weather.local\",\"state\":\"cloudy\",\"attributes\":{}}]";
        assertEquals("阴", HaWeather.display(HaWeather.parse(body), true));
    }

    /** 没有可用天气时返回空。 */
    @Test
    public void emptyWhenNoWeather() {
        assertNull(HaWeather.parse("[{\"entity_id\":\"sun.sun\",\"state\":\"above_horizon\",\"attributes\":{}}]"));
        assertNull(HaWeather.display(null, true));
    }
}
