#!/usr/bin/env python3
"""
Train GNN models (link prediction + node classification) and export to ONNX.

Usage:
    python scripts/train_gnn.py \\
        --graph-data graph_export.json \\
        --output-dir models/ \\
        --epochs 100

Requires: pip install torch torch-geometric onnx numpy
"""
import argparse
import json

import numpy as np
import torch
from torch_geometric.data import Data
from torch_geometric.nn import GCNConv


class LinkPredGNN(torch.nn.Module):
    def __init__(self, in_channels, hidden_channels):
        super().__init__()
        self.conv1 = GCNConv(in_channels, hidden_channels)
        self.conv2 = GCNConv(hidden_channels, hidden_channels)

    def encode(self, x, edge_index):
        x = self.conv1(x, edge_index).relu()
        return self.conv2(x, edge_index)

    def decode(self, z, edge_label_index):
        return (z[edge_label_index[0]] * z[edge_label_index[1]]).sum(dim=-1)

    def forward(self, x, edge_index):
        return self.encode(x, edge_index)


class NodeClassGNN(torch.nn.Module):
    def __init__(self, in_channels, hidden_channels, num_classes):
        super().__init__()
        self.conv1 = GCNConv(in_channels, hidden_channels)
        self.conv2 = GCNConv(hidden_channels, num_classes)

    def forward(self, x, edge_index):
        x = self.conv1(x, edge_index).relu()
        x = torch.nn.functional.dropout(x, p=0.5, training=self.training)
        return self.conv2(x, edge_index)


def load_graph(path):
    with open(path) as f:
        data = json.load(f)
    x = torch.tensor(data["node_features"], dtype=torch.float)
    edge_index = torch.tensor(data["edge_index"], dtype=torch.long)
    if "node_labels" in data:
        y = torch.tensor(data["node_labels"], dtype=torch.long)
    else:
        y = torch.zeros(len(data["node_features"]), dtype=torch.long)
    return Data(x=x, edge_index=edge_index, y=y)


def train_and_export(graph_path, output_dir, epochs):
    data = load_graph(graph_path)
    in_dim = data.x.shape[1]

    # Link prediction model
    link_model = LinkPredGNN(in_dim, 64)
    link_opt = torch.optim.Adam(link_model.parameters(), lr=0.01)
    for epoch in range(epochs):
        link_model.train()
        z = link_model.encode(data.x, data.edge_index)
        loss = -torch.log(torch.sigmoid(link_model.decode(z, data.edge_index)) + 1e-15).mean()
        loss.backward()
        link_opt.step()
        link_opt.zero_grad()
        if epoch % 20 == 0:
            print(f"Link pred epoch {epoch}: loss={loss.item():.4f}")

    link_model.eval()
    torch.onnx.export(
        link_model,
        (data.x, data.edge_index),
        f"{output_dir}/gnn_link_pred.onnx",
        input_names=["node_features", "edge_index"],
        dynamic_axes={
            "node_features": {0: "num_nodes"},
            "edge_index": {1: "num_edges"},
        },
    )
    print(f"Link prediction model exported to {output_dir}/gnn_link_pred.onnx")

    # Node classification model
    num_classes = int(data.y.max().item()) + 1 if data.y.numel() > 0 and data.y.max() > 0 else 7
    node_model = NodeClassGNN(in_dim, 64, num_classes)
    node_opt = torch.optim.Adam(node_model.parameters(), lr=0.01)
    for epoch in range(epochs):
        node_model.train()
        out = node_model(data.x, data.edge_index)
        loss = torch.nn.functional.cross_entropy(out, data.y)
        loss.backward()
        node_opt.step()
        node_opt.zero_grad()
        if epoch % 20 == 0:
            print(f"Node class epoch {epoch}: loss={loss.item():.4f}")

    node_model.eval()
    torch.onnx.export(
        node_model,
        (data.x, data.edge_index),
        f"{output_dir}/gnn_node_class.onnx",
        input_names=["node_features", "edge_index"],
        dynamic_axes={
            "node_features": {0: "num_nodes"},
            "edge_index": {1: "num_edges"},
        },
    )
    print(f"Node classification model exported to {output_dir}/gnn_node_class.onnx")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--graph-data", required=True, help="Path to graph_export.json")
    parser.add_argument("--output-dir", default="models/", help="Output directory for ONNX files")
    parser.add_argument("--epochs", type=int, default=100, help="Training epochs")
    args = parser.parse_args()
    train_and_export(args.graph_data, args.output_dir, args.epochs)
