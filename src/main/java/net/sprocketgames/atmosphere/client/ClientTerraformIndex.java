package net.sprocketgames.atmosphere.client;

/**
 * Client-side holder for the Terraform Index synchronized from the server.
 */
public final class ClientTerraformIndex {
    private static long terraformIndex;

    private ClientTerraformIndex() {
    }

    public static long getTerraformIndex() {
        return terraformIndex;
    }

    public static void setTerraformIndex(long terraformIndex) {
        ClientTerraformIndex.terraformIndex = terraformIndex;
    }
}
